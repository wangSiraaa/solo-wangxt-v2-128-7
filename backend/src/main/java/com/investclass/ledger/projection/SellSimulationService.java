package com.investclass.ledger.projection;

import com.investclass.ledger.core.AccountingProperties;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import com.investclass.ledger.core.MoneyMath;
import com.investclass.ledger.ledger.EventRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 卖出试算（what-if，只读）：课堂上正式导入卖出事件前，先回答
 * “这笔卖出会消耗哪些 FIFO 批次、剩多少整股/零碎股、结转多少成本、预计损益多少”。
 *
 * 做法：把一笔假设卖出按与真实成交完全相同的效应规则（在途拆股折算、复权价、
 * 规范排序）插入效应流，在一次性内存 {@link FoldState} 上折叠到试算结算点，
 * 报告结果后即丢弃。
 *
 * 只读保证：不追加事件、不写投影表、不推进游标、不发布快照；
 * 超卖按现有规则给出缺口（试算数量封顶在可卖数量，绝不产生负批次）；
 * 未结算持仓不在批次中，自然不可卖；精度尾差由 {@link FoldEngine} 的
 * 既有结转规则处理（部分卖出的舍入尾差留在本批次）。
 */
@Service
public class SellSimulationService {

    private final EventRepository events;
    private final AccountingProperties props;

    public SellSimulationService(EventRepository events, AccountingProperties props) {
        this.events = events;
        this.props = props;
    }

    /** 试算输入：账户在路径上；股数与成交价为交易单位口径（与真实卖出事件一致）。 */
    public record Request(String instrument, LocalDate tradeDate, LocalDate settlementDate,
                          BigDecimal quantity, BigDecimal price) {
    }

    /** 单个批次的预计消耗（FIFO 顺序）。 */
    public record Take(String lotKey, BigDecimal qty, BigDecimal costReleased,
                       BigDecimal proceeds) {
    }

    /** 试算后的批次快照（含被本次试算结清的批次与衍生零碎批）。 */
    public record LotAfter(String lotKey, BigDecimal remainingQty, BigDecimal remainingCost,
                           BigDecimal unitCost, boolean fractional, boolean closed,
                           String derivedFromLotKey) {
    }

    /**
     * @param settledQty       在途拆股折算后的结算股数
     * @param availableQty     试算结算点在册可卖数量（未结算持仓不计入）
     * @param simulatedQty     实际参与试算的股数 = min(settledQty, availableQty)
     * @param shortfall        缺口：超出可卖数量的部分（0 表示足额可卖）
     * @param booked           恒为 false —— 试算结果非正式入账
     */
    public record Result(String accountId, String instrument,
                         LocalDate tradeDate, LocalDate settlementDate,
                         BigDecimal requestedQty, BigDecimal price,
                         BigDecimal pendingSplitRatio, BigDecimal settledQty,
                         BigDecimal adjustedPrice,
                         BigDecimal availableQty, BigDecimal simulatedQty,
                         boolean feasible, BigDecimal shortfall,
                         List<Take> takes,
                         BigDecimal costReleased, BigDecimal proceeds, BigDecimal expectedPnl,
                         List<FoldEngine.PositionView> positionsBefore,
                         List<FoldEngine.PositionView> positionsAfter,
                         List<LotAfter> lotsAfter,
                         boolean booked) {
    }

    public Result simulate(String accountId, Request req) {
        validate(req);
        // 事件账本是唯一事实源：试算从全部现有事件出发（只读查询）
        List<Event> all = events.findByAccountUpTo(accountId, Long.MAX_VALUE);
        // 假设事件 id 取当前最大 id + 1：视同“现在才到达”，同日期同阶段排在最后
        long simId = all.stream().mapToLong(Event::id).max().orElse(0L) + 1L;

        Effect sell = EffectPlanner.hypotheticalSell(all, simId, req.instrument(),
                req.tradeDate(), req.settlementDate(), req.quantity(), req.price());
        List<Effect> planned = EffectPlanner.planWithExtra(all, sell);

        // 假设事件仅存在于内存：FoldEngine 记现金行需要源事件的业务日
        Event simEvent = new Event(simId, EventType.TRADE, accountId, req.instrument(),
                req.tradeDate(), req.settlementDate(), null, null, null,
                new EventPayload.Trade("SELL", req.quantity(), req.price(),
                        BigDecimal.ZERO, "CNY"),
                "what-if", "SIM-" + simId, "sim-" + simId, false, null);
        Map<Long, Event> byId = all.stream().collect(Collectors.toMap(Event::id, e -> e));
        byId.put(simId, simEvent);

        // 折叠到试算结算点（假设卖出之前的全部效应；结算日晚于该点的买入尚不在批次中）
        FoldState state = new FoldState(props);
        for (Effect eff : planned) {
            if (eff.eventId() == simId) {
                break;
            }
            FoldEngine.apply(state, eff, byId.get(eff.eventId()), accountId);
        }
        List<FoldEngine.PositionView> before = FoldEngine.positions(state);

        // 可卖数量 = 结算点在册未结清批次合计；超卖给出缺口并封顶试算数量，不产生负批次
        BigDecimal available = BigDecimal.ZERO;
        for (Lot l : state.lots.values()) {
            if (l.instrument().equals(req.instrument()) && l.isOpen()) {
                available = available.add(l.remainingQty());
            }
        }
        available = MoneyMath.shares(available, props);
        BigDecimal settledQty = MoneyMath.shares(sell.qty(), props);
        BigDecimal simulatedQty = settledQty.min(available);
        BigDecimal shortfall = MoneyMath.shares(settledQty.subtract(simulatedQty), props);
        boolean feasible = shortfall.signum() == 0;

        List<Take> takes = new ArrayList<>();
        BigDecimal costReleased = BigDecimal.ZERO;
        BigDecimal proceeds = BigDecimal.ZERO;
        if (simulatedQty.signum() > 0) {
            Effect toApply = sell;
            if (!feasible) {
                // 超出可卖数量：仅试算可卖部分，收入按复权价等比例折算
                BigDecimal cappedGross = MoneyMath.cash(
                        simulatedQty.multiply(sell.price()), props);
                toApply = new Effect(sell.effectKey(), sell.eventId(), sell.effectiveDate(),
                        sell.stage(), sell.kind(), sell.instrument(), simulatedQty, cappedGross,
                        sell.price(), sell.fee(), sell.ratio(), sell.targetLotKeys());
            }
            FoldEngine.apply(state, toApply, simEvent, accountId);
            for (Map.Entry<String, LotConsumption> en : state.consumptions.entrySet()) {
                if (en.getKey().startsWith(simId + ":")) {
                    LotConsumption c = en.getValue();
                    takes.add(new Take(c.lotKey(), c.qty(), c.costReleased(), c.proceeds()));
                    costReleased = costReleased.add(c.costReleased());
                    proceeds = proceeds.add(c.proceeds());
                }
            }
        }
        costReleased = MoneyMath.cash(costReleased, props);
        proceeds = MoneyMath.cash(proceeds, props);
        BigDecimal expectedPnl = MoneyMath.cash(proceeds.subtract(costReleased), props);

        List<LotAfter> lotsAfter = new ArrayList<>();
        for (Lot l : state.lots.values()) {
            if (l.instrument().equals(req.instrument())) {
                lotsAfter.add(new LotAfter(l.lotKey(), l.remainingQty(), l.remainingCost(),
                        l.unitCost(), l.fractional(), l.closed(), l.derivedFromLotKey()));
            }
        }
        return new Result(accountId, req.instrument(), req.tradeDate(), req.settlementDate(),
                MoneyMath.shares(req.quantity(), props), MoneyMath.price(req.price(), props),
                sell.ratio(), settledQty, sell.price(),
                available, simulatedQty, feasible, shortfall,
                takes, costReleased, proceeds, expectedPnl,
                before, FoldEngine.positions(state), lotsAfter, false);
    }

    private static void validate(Request req) {
        if (req == null || req.instrument() == null || req.instrument().isBlank()) {
            throw new IllegalArgumentException("instrument is required");
        }
        if (req.tradeDate() == null || req.settlementDate() == null) {
            throw new IllegalArgumentException("tradeDate and settlementDate are required");
        }
        if (req.settlementDate().isBefore(req.tradeDate())) {
            throw new IllegalArgumentException("settlementDate must not be before tradeDate");
        }
        if (req.quantity() == null || req.quantity().signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (req.price() == null || req.price().signum() < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
    }
}
