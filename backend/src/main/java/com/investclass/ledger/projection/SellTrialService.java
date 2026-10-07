package com.investclass.ledger.projection;

import com.investclass.ledger.core.AccountingProperties;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventType;
import com.investclass.ledger.core.MoneyMath;
import com.investclass.ledger.ledger.EventRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读卖出试算（课堂“非正式入账”预演）：
 *
 * <ul>
 *   <li>只从事件账本按规范顺序重放“截至试算结算点”（效应生效日 &lt;= 结算日）的现有规则，
 *       得到试算前的 FIFO 批次；在副本状态上结转，真实投影/检查点/游标/快照一律不触碰。</li>
 *   <li>不追加任何事件、不推进游标、不发布快照、不写现金行；纯内存计算。</li>
 *   <li>预计：逐批结转（成本尾差留在本批次）、剩余整股/零碎股、结转成本、预计损益。</li>
 *   <li>超卖按现有规则给出缺口（shortfallQty），只结转可卖部分，不产生负批次；
 *       未在结算日（含）前交割的在途买入不计入可卖、仅列入 unsettled 提示。</li>
 * </ul>
 */
@Service
public class SellTrialService {

    /** 所有试算结果统一标识：非正式入账，不能反向补造历史。 */
    public static final String ENTRY_KIND = "PROFORMA_NOT_BOOKED";

    private final EventRepository events;
    private final AccountingProperties props;

    public SellTrialService(EventRepository events, AccountingProperties props) {
        this.events = events;
        this.props = props;
    }

    /** 逐批预计结转（与真实 LotConsumption 同口径，但永不落库）。 */
    public record TakeView(String lotKey, Long openingEventId, String sourceEventType,
                           BigDecimal qty, BigDecimal costReleased, BigDecimal proceeds) {
    }

    /** 被试算触及的批次：试算前后数量/成本对照（closed 表示试算后结清）。 */
    public record LotView(String lotKey, Long openingEventId, String sourceEventType,
                          LocalDate acquiredDate, BigDecimal remainingQtyBefore,
                          BigDecimal remainingCostBefore, BigDecimal remainingQtyAfter,
                          BigDecimal remainingCostAfter, boolean fractional, boolean closed,
                          boolean newLot, String derivedFromLotKey) {
    }

    /** 截至结算点尚未交割的在途买入（不可提前卖出，仅提示，不进可卖数量）。 */
    public record PendingSettlement(Long eventId, LocalDate businessDate,
                                    LocalDate settlementDate, BigDecimal quantity) {
    }

    public record PositionView(String instrument, BigDecimal wholeQty, BigDecimal fractionalQty,
                               BigDecimal remainingCost, BigDecimal avgCost) {
    }

    /**
     * 试算报告。{@code booked=false / entryKind=PROFORMA_NOT_BOOKED} 明示非正式入账。
     *
     * @param requestedQty  输入的原始成交单位股数
     * @param settledQty    按 (交易日, 结算日] 拆股连乘积折算后的结算单位股数
     * @param sellableQty   截至结算点该券可卖（整股+零碎股）总量
     * @param coveredQty    本次试算实际结转的股数（超卖时 = 可卖量）
     * @param shortfallQty  缺口（>0 即超卖；不产生负批次）
     * @param oversold      是否超卖
     * @param grossProceeds 按原始成交额口径的预计成交总额（覆盖部分）
     * @param commission    预计佣金（超卖时只按成交覆盖部分分摊，缺口不收费）
     * @param netProceeds   预计净收入（现金精度；= 覆盖部分毛收入 - 预计佣金）
     * @param costReleased  预计结转成本合计
     * @param realizedPnl   预计实现损益（净收入 - 结转成本）
     */
    public record Report(String accountId, String instrument,
                         LocalDate businessDate, LocalDate settlementDate,
                         BigDecimal requestedQty, BigDecimal price, BigDecimal commission,
                         BigDecimal pendingSplitRatio, BigDecimal settledQty,
                         BigDecimal adjustedPrice,
                         BigDecimal sellableQty, BigDecimal coveredQty,
                         BigDecimal shortfallQty, boolean oversold,
                         BigDecimal grossProceeds, BigDecimal netProceeds,
                         BigDecimal costReleased, BigDecimal realizedPnl,
                         PositionView before, PositionView after,
                         List<TakeView> consumptions, List<LotView> lotChanges,
                         List<PendingSettlement> unsettled, boolean booked, String entryKind) {
    }

    /**
     * 执行卖出试算。
     *
     * @param quantity 原始成交单位股数（在途遇拆股时按现有成交规则折算）
     * @param price    原始成交单位每股价格（在途遇拆股时复权）
     */
    public Report trial(String accountId, String instrument, LocalDate businessDate,
                        LocalDate settlementDate, BigDecimal quantity, BigDecimal price,
                        BigDecimal commission) {
        if (businessDate == null || settlementDate == null) {
            throw new IllegalArgumentException("businessDate 与 settlementDate 必填");
        }
        if (settlementDate.isBefore(businessDate)) {
            throw new IllegalArgumentException("结算日不能早于业务日（不可提前交割）");
        }
        if (instrument == null || instrument.isBlank()) {
            throw new IllegalArgumentException("instrument 必填");
        }
        BigDecimal reqQty = MoneyMath.shares(quantity, props);
        BigDecimal priceD = MoneyMath.price(price, props);
        BigDecimal fee = commission == null ? BigDecimal.ZERO : MoneyMath.cash(commission, props);
        if (reqQty.signum() <= 0) {
            throw new IllegalArgumentException("卖出股数必须为正");
        }
        if (priceD.signum() < 0) {
            throw new IllegalArgumentException("价格不能为负");
        }

        // 截至试算结算点的事件窗口：business_date <= 结算日。
        // 效应再按生效日 <= 结算日过滤，因此结算日之后才交割的买入不形成可卖持仓。
        List<Event> window = events.findByAccountForDate(accountId, settlementDate);

        // 在途拆股折算（复用成交计划的现有规则）：(业务日, 结算日] 连乘积
        BigDecimal ratio = EffectPlanner.splitProduct(window, instrument,
                businessDate, settlementDate, -1L);
        BigDecimal settledQty = MoneyMath.shares(reqQty.multiply(ratio), props);
        BigDecimal adjustedPrice = MoneyMath.price(
                priceD.divide(ratio, props.scale().price(), RoundingMode.HALF_UP), props);

        FoldState base = foldAtSettlement(window, accountId, settlementDate);
        PositionView before = positionOf(base, instrument);
        BigDecimal sellable = MoneyMath.shares(
                before.wholeQty().add(before.fractionalQty()), props);

        // 原始成交额口径（与真实成交一致：qty*price）。超卖时收入只按可成交覆盖部分计，
        // 缺口部分不成交：不产生收入、不收取佣金，也不产生负批次。
        BigDecimal grossFull = MoneyMath.cash(reqQty.multiply(priceD), props);
        BigDecimal coveredRatio = settledQty.signum() == 0
                ? BigDecimal.ZERO
                : settledQty.min(sellable).divide(settledQty, props.scale().cash(),
                        RoundingMode.HALF_UP);
        BigDecimal grossCovered = MoneyMath.cash(grossFull.multiply(coveredRatio), props);
        BigDecimal coveredFee = MoneyMath.cash(fee.multiply(coveredRatio), props);
        BigDecimal netProceeds = MoneyMath.cash(grossCovered.subtract(coveredFee), props);

        Map<String, Lot> lotsBefore = new LinkedHashMap<>(base.lots);
        FoldState trial = base.copy();
        FoldEngine.TrialSellResult r = FoldEngine.simulateSell(trial, instrument,
                settlementDate, settledQty, netProceeds);
        PositionView after = positionOf(trial, instrument);

        Map<String, Lot> lotsAfter = trial.lots;
        List<LotView> lotChanges = new java.util.ArrayList<>();
        for (Map.Entry<String, Lot> en : lotsAfter.entrySet()) {
            String key = en.getKey();
            Lot a = en.getValue();
            if (!a.instrument().equals(instrument)) {
                continue;
            }
            Lot b = lotsBefore.get(key);
            boolean newLot = b == null;
            // 只列试算真正触及的批次：新拆出的零碎批，或数量/成本发生变化的批次
            boolean changed = newLot
                    || b.remainingQty().compareTo(a.remainingQty()) != 0
                    || b.remainingCost().compareTo(a.remainingCost()) != 0
                    || b.closed() != a.closed();
            if (!changed) {
                continue;
            }
            lotChanges.add(new LotView(key, a.openingEventId(), a.sourceEventType(),
                    a.acquiredDate(),
                    newLot ? BigDecimal.ZERO.setScale(props.scale().shares())
                            : b.remainingQty(),
                    newLot ? BigDecimal.ZERO.setScale(props.scale().cash())
                            : b.remainingCost(),
                    a.remainingQty(), a.remainingCost(), a.fractional(), !a.isOpen(),
                    newLot, a.derivedFromLotKey()));
        }

        List<TakeView> takes = r.takes().stream()
                .map(t -> new TakeView(t.lotKey(), t.openingEventId(), t.sourceEventType(),
                        t.qty(), t.costReleased(), t.proceeds()))
                .toList();

        List<PendingSettlement> unsettled = window.stream()
                .filter(e -> e.type() == EventType.TRADE && e.instrument().equals(instrument)
                        && e.payload().asTrade().isBuy()
                        && e.settlementDate() != null
                        && e.settlementDate().isAfter(settlementDate))
                .map(e -> new PendingSettlement(e.id(), e.businessDate(), e.settlementDate(),
                        MoneyMath.shares(e.payload().asTrade().quantity(), props)))
                .toList();

        BigDecimal realizedPnl = MoneyMath.cash(netProceeds.subtract(r.costReleasedTotal()),
                props);

        return new Report(accountId, instrument, businessDate, settlementDate,
                reqQty, priceD, coveredFee, ratio, settledQty, adjustedPrice,
                sellable, r.coveredQty(),
                r.shortfallQty(), r.shortfallQty().signum() > 0,
                grossCovered, netProceeds, r.costReleasedTotal(), realizedPnl,
                before, after, takes, lotChanges, unsettled, false, ENTRY_KIND);
    }

    /** 仅重放生效日 <= 结算日的效应（内存），得到截至试算结算点的批次/现金状态。 */
    private FoldState foldAtSettlement(List<Event> window, String accountId,
                                       LocalDate settlementDate) {
        FoldState s = new FoldState(props);
        Map<Long, Event> byId = new LinkedHashMap<>();
        window.forEach(e -> byId.put(e.id(), e));
        for (Effect eff : EffectPlanner.plan(window).effects()) {
            if (eff.effectiveDate().isAfter(settlementDate)) {
                continue; // 结算日之后才生效（结算/支付/到账）的一律不参与
            }
            FoldEngine.apply(s, eff, byId.get(eff.eventId()), accountId);
        }
        return s;
    }

    private PositionView positionOf(FoldState s, String instrument) {
        return FoldEngine.positions(s).stream()
                .filter(p -> p.instrument().equals(instrument))
                .findFirst()
                .map(p -> new PositionView(p.instrument(), p.qty(), p.fractionalQty(),
                        p.openCost(), p.avgCost()))
                .orElseGet(() -> new PositionView(instrument,
                        BigDecimal.ZERO.setScale(props.scale().shares()),
                        BigDecimal.ZERO.setScale(props.scale().shares()),
                        BigDecimal.ZERO.setScale(props.scale().cash()),
                        BigDecimal.ZERO.setScale(props.scale().lotCost())));
    }
}
