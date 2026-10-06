package com.investclass.ledger.projection;

import com.investclass.ledger.core.AccountingException;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.MoneyMath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯函数效应折叠器：把一个规范排序的效应流应用到 {@link FoldState}。
 *
 * 幂等：所有写入都带稳定键（lotKey / cash idemKey / entitlement idemKey /
 * consumption 唯一键 / 拆股 adjustedByEventId），同一效应重复应用是 no-op。
 * 因此“权益已计算、现金尚未入账”处崩溃后从一致游标重放，不会重复增股或重复分红。
 *
 * 守恒：
 *  - 数量：卖出逐批扣减，拆股按比例缩放；零碎股单独成批。
 *  - 现金：仅结算日/支付日记入，逐笔唯一。
 *  - 批次成本：FIFO 结转，最后一批吸收舍入尾差，批次剩余成本精确到分守恒。
 */
public final class FoldEngine {

    private FoldEngine() {
    }

    public static void applyAll(FoldState state, List<Effect> effects,
                                Map<Long, Event> eventsById, String accountId) {
        for (Effect eff : effects) {
            apply(state, eff, eventsById.get(eff.eventId()), accountId);
        }
    }

    public static void apply(FoldState s, Effect eff, Event src, String accountId) {
        s.beginEffect();
        switch (eff.kind()) {
            case BUY_SETTLE -> applyBuy(s, eff, src, accountId);
            case SELL_SETTLE -> applySell(s, eff, src, accountId);
            case SPLIT_ADJUST -> applySplit(s, eff);
            case DIVIDEND_ENTITLE -> putEntitlement(s, eff, "CALCULATED", BigDecimal.ZERO);
            case DIVIDEND_PAY -> applyDividendPay(s, eff, accountId);
            case RIGHTS_ENTITLE -> putEntitlement(s, eff, "CALCULATED", BigDecimal.ZERO);
            case RIGHTS_PAY -> applyRightsPay(s, eff, accountId);
            case RIGHTS_ALLOT -> applyRightsAllot(s, eff);
        }
    }

    // ---------------- 成交 ----------------

    private static void applyBuy(FoldState s, Effect eff, Event src, String accountId) {
        String key = "L" + eff.eventId() + ":BUY";
        if (s.lots.containsKey(key)) {
            return; // 重放已生效
        }
        BigDecimal fee = eff.fee() == null ? BigDecimal.ZERO : eff.fee();
        BigDecimal totalCost = MoneyMath.cash(eff.amount(), s.props);
        BigDecimal unit = eff.qty().signum() == 0 ? BigDecimal.ZERO
                : MoneyMath.unitCost(totalCost.divide(eff.qty(), RoundingMode.HALF_UP), s.props);
        Lot lot = Lot.open(key, eff.eventId(), "TRADE", eff.instrument(), eff.effectiveDate(),
                eff.qty(), totalCost, unit, MoneyMath.isFractional(eff.qty(), s.props));
        s.putLot(key, lot);
        // amount 已含佣金；佣金作为同一现金效应的独立明细另列一行，总额不重复
        BigDecimal gross = MoneyMath.cash(totalCost.subtract(fee), s.props);
        s.cash.put(cashKey(eff, "TRADE_BUY"), new CashEntry(eff.eventId(), src.businessDate(),
                eff.effectiveDate(), eff.effectKey(), "OUT", gross, "TRADE_BUY",
                accountId + ":" + eff.effectKey() + ":TRADE_BUY"));
        if (fee.signum() > 0) {
            s.cash.put(cashKey(eff, "COMMISSION"), new CashEntry(eff.eventId(),
                    src.businessDate(), eff.effectiveDate(), eff.effectKey(),
                    "OUT", fee, "COMMISSION",
                    accountId + ":" + eff.effectKey() + ":COMMISSION"));
        }
    }

    private static void applySell(FoldState s, Effect eff, Event src, String accountId) {
        // 消费幂等：该卖出的批次结转都在同一效应里
        boolean already = s.consumptions.keySet().stream()
                .anyMatch(k -> k.startsWith(eff.eventId() + ":"));
        if (already) {
            return;
        }
        BigDecimal fee = eff.fee() == null ? BigDecimal.ZERO : eff.fee();
        // amount 是卖出净额（gross - fee）；现金拆成收入行 + 佣金行，两者不重复
        BigDecimal proceeds = MoneyMath.cash(eff.amount(), s.props);
        BigDecimal grossIn = MoneyMath.cash(proceeds.add(fee), s.props);

        List<Lot> candidates = s.lots.values().stream()
                .filter(l -> l.instrument().equals(eff.instrument()) && l.isOpen())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        BigDecimal need = MoneyMath.shares(eff.qty(), s.props);
        List<LotTake> takes = new ArrayList<>();
        for (Lot lot : candidates) {
            if (need.signum() == 0) {
                break;
            }
            BigDecimal take = need.min(lot.remainingQty());
            need = MoneyMath.shares(need.subtract(take), s.props);
            takes.add(new LotTake(lot, take));
        }
        if (need.signum() > 0) {
            throw new AccountingException(eff.eventId(),
                    "oversell " + eff.instrument() + " qty=" + eff.qty()
                            + " short by " + need + " on settlement " + eff.effectiveDate());
        }

        // 成本结转（每个批次独立精确到分）：
        //  - 完全清空：释放全部剩余成本；
        //  - 部分卖出：新剩余成本按剩余比例舍入，released = 旧成本 - 新成本，
        //    舍入尾差留在本批次自身，不跨批泄漏。
        List<BigDecimal> costs = new ArrayList<>();
        BigDecimal releasedTotal = BigDecimal.ZERO;
        for (LotTake tk : takes) {
            BigDecimal cost;
            if (tk.qty.compareTo(tk.lot.remainingQty()) == 0) {
                cost = tk.lot.remainingCost();
            } else {
                BigDecimal leftQty = MoneyMath.shares(
                        tk.lot.remainingQty().subtract(tk.qty), s.props);
                BigDecimal leftCost = MoneyMath.cash(tk.lot.remainingCost()
                        .multiply(leftQty)
                        .divide(tk.lot.remainingQty(), RoundingMode.HALF_UP), s.props);
                cost = MoneyMath.cash(tk.lot.remainingCost().subtract(leftCost), s.props);
            }
            costs.add(cost);
            releasedTotal = releasedTotal.add(cost);
        }

        // 收入按释放成本比例分配到结转行，最后一笔吸收尾差
        BigDecimal allocated = BigDecimal.ZERO;
        // 卖出后若剩余数量含零头，整股与零碎股拆成两批（零碎股单列），保持 FIFO 顺序
        record Residual(String parentKey, Lot whole, Lot frac) {
        }
        List<Residual> residuals = new ArrayList<>();
        for (int i = 0; i < takes.size(); i++) {
            LotTake tk = takes.get(i);
            BigDecimal cost = costs.get(i);
            BigDecimal part;
            if (i == takes.size() - 1) {
                part = proceeds.subtract(allocated);
            } else if (releasedTotal.signum() == 0) {
                part = BigDecimal.ZERO.setScale(s.props.scale().cash());
            } else {
                part = MoneyMath.cash(proceeds.multiply(cost)
                        .divide(releasedTotal, RoundingMode.HALF_UP), s.props);
            }
            allocated = allocated.add(part);

            LotConsumption c = new LotConsumption(tk.lot.lotKey(), eff.eventId(),
                    eff.instrument(), eff.effectiveDate(), tk.qty, cost, part);
            s.consumptions.put(eff.eventId() + ":" + tk.lot.lotKey(), c);

            BigDecimal newQty = MoneyMath.shares(tk.lot.remainingQty().subtract(tk.qty), s.props);
            BigDecimal newCost = MoneyMath.cash(tk.lot.remainingCost().subtract(cost), s.props);
            boolean closed = newQty.signum() == 0;
            Lot whole;
            Lot fracLot = null;
            if (!closed && MoneyMath.isFractional(newQty, s.props)) {
                BigDecimal wholeQty = newQty.setScale(0, RoundingMode.FLOOR);
                BigDecimal fracQty = MoneyMath.shares(newQty.subtract(wholeQty), s.props);
                BigDecimal wholeCost = wholeQty.signum() == 0 ? BigDecimal.ZERO
                        : MoneyMath.cash(newCost.multiply(wholeQty)
                                .divide(newQty, RoundingMode.HALF_UP), s.props);
                BigDecimal fracCost = MoneyMath.cash(newCost.subtract(wholeCost), s.props);
                BigDecimal wholeUnit = wholeQty.signum() == 0 ? BigDecimal.ZERO
                        : MoneyMath.unitCost(wholeCost.divide(wholeQty, RoundingMode.HALF_UP),
                                s.props);
                BigDecimal fracUnit = MoneyMath.unitCost(
                        fracCost.divide(fracQty, RoundingMode.HALF_UP), s.props);
                if (wholeQty.signum() == 0) {
                    // 剩余不足 1 股：原批次关闭，另开零碎批
                    whole = new Lot(tk.lot.lotKey(), tk.lot.openingEventId(),
                            tk.lot.sourceEventType(), tk.lot.instrument(), tk.lot.acquiredDate(),
                            tk.lot.openQty(), tk.lot.remainingQty(), tk.lot.totalCost(),
                            tk.lot.remainingCost(), tk.lot.unitCost(), tk.lot.fractional(),
                            true, tk.lot.derivedFromLotKey(), tk.lot.adjustedByEventId());
                } else {
                    whole = new Lot(tk.lot.lotKey(), tk.lot.openingEventId(),
                            tk.lot.sourceEventType(), tk.lot.instrument(), tk.lot.acquiredDate(),
                            tk.lot.openQty(), wholeQty, tk.lot.totalCost(), wholeCost,
                            wholeUnit, false, false, tk.lot.derivedFromLotKey(),
                            tk.lot.adjustedByEventId());
                }
                String fracKey = "L" + eff.eventId() + ":FRAC:" + tk.lot.lotKey();
                fracLot = new Lot(fracKey, tk.lot.openingEventId(),
                        tk.lot.sourceEventType(), tk.lot.instrument(), tk.lot.acquiredDate(),
                        tk.lot.openQty(), fracQty, tk.lot.totalCost(), fracCost,
                        fracUnit, true, false, tk.lot.lotKey(), tk.lot.adjustedByEventId());
            } else {
                whole = new Lot(tk.lot.lotKey(), tk.lot.openingEventId(),
                        tk.lot.sourceEventType(), tk.lot.instrument(), tk.lot.acquiredDate(),
                        tk.lot.openQty(), newQty, tk.lot.totalCost(), newCost, tk.lot.unitCost(),
                        tk.lot.fractional(), closed, tk.lot.derivedFromLotKey(),
                        tk.lot.adjustedByEventId());
            }
            s.putLot(tk.lot.lotKey(), whole);
            if (fracLot != null) {
                residuals.add(new Residual(tk.lot.lotKey(), whole, fracLot));
            }
        }
        if (!residuals.isEmpty()) {
            Map<String, Lot> reordered = new LinkedHashMap<>();
            for (Map.Entry<String, Lot> en : s.lots.entrySet()) {
                reordered.put(en.getKey(), en.getValue());
                for (Residual r : residuals) {
                    if (r.parentKey().equals(en.getKey())) {
                        reordered.put(r.frac().lotKey(), r.frac());
                    }
                }
            }
            s.lots.clear();
            s.lots.putAll(reordered);
            for (Residual r : residuals) {
                s.markChangedLot(r.frac().lotKey());
                s.markChangedLot(r.parentKey());
            }
        }
        s.realizedPnl = MoneyMath.cash(s.realizedPnl.add(proceeds).subtract(releasedTotal),
                s.props);

        s.cash.put(cashKey(eff, "TRADE_SELL"), new CashEntry(eff.eventId(),
                src.businessDate(), eff.effectiveDate(), eff.effectKey(),
                "IN", grossIn, "TRADE_SELL",
                accountId + ":" + eff.effectKey() + ":TRADE_SELL"));
        if (fee.signum() > 0) {
            s.cash.put(cashKey(eff, "COMMISSION"), new CashEntry(eff.eventId(),
                    src.businessDate(), eff.effectiveDate(), eff.effectKey(),
                    "OUT", fee, "COMMISSION",
                    accountId + ":" + eff.effectKey() + ":COMMISSION"));
        }
    }

    // ---------------- 拆股 ----------------

    private static void applySplit(FoldState s, Effect eff) {
        BigDecimal ratio = eff.qty(); // 比例放在 qty 字段
        for (Lot lot : new ArrayList<>(s.lots.values())) {
            if (!lot.instrument().equals(eff.instrument()) || !lot.isOpen()) {
                continue;
            }
            if (!lot.acquiredDate().isBefore(eff.effectiveDate())
                    && !lot.acquiredDate().isEqual(eff.effectiveDate())) {
                continue; // 除权日之后才到账的批次不参与（按生效顺序自然不会出现，防御性判断）
            }
            if (eff.eventId() == (long) unbox(lot.adjustedByEventId())) {
                continue;
            }
            BigDecimal newOpen = MoneyMath.shares(lot.openQty().multiply(ratio), s.props);
            BigDecimal newQty = MoneyMath.shares(lot.remainingQty().multiply(ratio), s.props);

            BigDecimal whole = newQty.setScale(0, RoundingMode.FLOOR);
            BigDecimal frac = MoneyMath.shares(newQty.subtract(whole), s.props);

            // 剩余成本精确切分：整股先舍，零碎股拿余数，二者之和 == 原剩余成本
            BigDecimal wholeCost = MoneyMath.cash(lot.remainingCost()
                    .multiply(whole)
                    .divide(newQty.signum() == 0 ? BigDecimal.ONE : newQty,
                            s.props.scale().cash(), RoundingMode.HALF_UP), s.props);
            BigDecimal fracCost = MoneyMath.cash(lot.remainingCost().subtract(wholeCost),
                    s.props);
            if (whole.signum() == 0) {
                wholeCost = BigDecimal.ZERO.setScale(s.props.scale().cash());
                fracCost = lot.remainingCost();
            }

            String origKey = lot.lotKey();
            // 原批次承载整股部分（整股为 0 时关闭）
            BigDecimal wholeUnit = whole.signum() == 0 ? BigDecimal.ZERO
                    : MoneyMath.unitCost(wholeCost.divide(whole, RoundingMode.HALF_UP), s.props);
            Lot wholeLot = new Lot(origKey, lot.openingEventId(), lot.sourceEventType(),
                    lot.instrument(), lot.acquiredDate(), newOpen, whole, lot.totalCost(),
                    wholeCost, wholeUnit, false, whole.signum() == 0,
                    lot.derivedFromLotKey(), eff.eventId());
            s.putLot(origKey, wholeLot);

            if (frac.signum() > 0) {
                String fracKey = "L" + eff.eventId() + ":FRAC:" + origKey;
                if (!s.lots.containsKey(fracKey)) {
                    BigDecimal fracUnit = MoneyMath.unitCost(
                            fracCost.divide(frac, RoundingMode.HALF_UP), s.props);
                    Lot fracLot = new Lot(fracKey, eff.eventId(), "STOCK_SPLIT",
                            lot.instrument(), lot.acquiredDate(),
                            frac, frac, lot.totalCost(), fracCost, fracUnit, true, false,
                            origKey, eff.eventId());
                    s.putLot(fracKey, fracLot);
                }
            }
        }
    }

    // ---------------- 分红 / 配股 ----------------

    private static void putEntitlement(FoldState s, Effect eff, String status,
                                       BigDecimal subscribed) {
        String idem = "E" + eff.eventId() + ":ENTITLE";
        if (s.entitlements.containsKey(idem)
                && "CALCULATED".equals(s.entitlements.get(idem).status())) {
            // 重放：只在尚未推进状态时保留首次计算
            return;
        }
        s.entitlements.putIfAbsent(idem, new Entitlement(eff.eventId(), eff.instrument(),
                eff.kind() == Effect.Kind.RIGHTS_ENTITLE ? "RIGHTS" : "CASH_DIVIDEND",
                eff.effectiveDate(), null, eff.qty(), eff.price(), eff.amount(),
                status, subscribed, idem));
    }

    private static void applyDividendPay(FoldState s, Effect eff, String accountId) {
        String idem = "E" + eff.eventId() + ":ENTITLE";
        Entitlement en = s.entitlements.get(idem);
        if (en != null && !"PAID".equals(en.status())) {
            s.entitlements.put(idem, en.withStatus("PAID", en.eligibleQty()));
        }
        String ck = accountId + ":" + eff.effectKey() + ":DIVIDEND";
        s.cash.putIfAbsent(ck, new CashEntry(eff.eventId(), eff.effectiveDate(),
                eff.effectiveDate(), eff.effectKey(), "IN", eff.amount(), "DIVIDEND", ck));
    }

    private static void applyRightsPay(FoldState s, Effect eff, String accountId) {
        String idem = "E" + eff.eventId() + ":ENTITLE";
        Entitlement en = s.entitlements.get(idem);
        String status;
        if (eff.qty().signum() == 0) {
            status = "EXPIRED";
        } else if (eff.qty().compareTo(en == null ? eff.qty() : en.eligibleQty()) == 0) {
            status = "SUBSCRIBED";
        } else {
            status = "PARTIALLY_SUBSCRIBED";
        }
        if (en != null && !"SUBSCRIBED".equals(en.status())
                && !"PARTIALLY_SUBSCRIBED".equals(en.status()) && !"EXPIRED".equals(en.status())) {
            s.entitlements.put(idem, en.withStatus(status, eff.qty()));
        }
        if (eff.amount().signum() > 0) {
            String ck = accountId + ":" + eff.effectKey() + ":RIGHTS_PAYMENT";
            s.cash.putIfAbsent(ck, new CashEntry(eff.eventId(), eff.effectiveDate(),
                    eff.effectiveDate(), eff.effectKey(), "OUT", eff.amount(),
                    "RIGHTS_PAYMENT", ck));
        }
    }

    private static void applyRightsAllot(FoldState s, Effect eff) {
        String key = "L" + eff.eventId() + ":ALLOT";
        if (s.lots.containsKey(key)) {
            return;
        }
        BigDecimal totalCost = MoneyMath.cash(eff.amount(), s.props);
        BigDecimal unit = MoneyMath.unitCost(
                totalCost.divide(eff.qty(), RoundingMode.HALF_UP), s.props);
        s.putLot(key, Lot.open(key, eff.eventId(), "RIGHTS_OFFER", eff.instrument(),
                eff.effectiveDate(), eff.qty(), totalCost, unit,
                MoneyMath.isFractional(eff.qty(), s.props)));
    }

    // ---------------- 汇总读取（供核对/快照） ----------------

    public record PositionView(String instrument, BigDecimal qty, BigDecimal fractionalQty,
                               BigDecimal openCost, BigDecimal avgCost) {
    }

    public static List<PositionView> positions(FoldState s) {
        Map<String, List<Lot>> byInstrument = new LinkedHashMap<>();
        for (Lot l : s.lots.values()) {
            if (l.isOpen()) {
                byInstrument.computeIfAbsent(l.instrument(), k -> new ArrayList<>()).add(l);
            }
        }
        List<PositionView> out = new ArrayList<>();
        for (var e : byInstrument.entrySet()) {
            BigDecimal qty = BigDecimal.ZERO;
            BigDecimal frac = BigDecimal.ZERO;
            BigDecimal cost = BigDecimal.ZERO;
            for (Lot l : e.getValue()) {
                if (l.fractional()) {
                    frac = frac.add(l.remainingQty());
                } else {
                    qty = qty.add(l.remainingQty());
                }
                cost = cost.add(l.remainingCost());
            }
            qty = MoneyMath.shares(qty, s.props);
            frac = MoneyMath.shares(frac, s.props);
            cost = MoneyMath.cash(cost, s.props);
            BigDecimal totalUnits = qty.add(frac);
            BigDecimal avg = totalUnits.signum() == 0 ? BigDecimal.ZERO
                    : MoneyMath.unitCost(cost.divide(totalUnits, RoundingMode.HALF_UP), s.props);
            out.add(new PositionView(e.getKey(), qty, frac, cost, avg));
        }
        return out;
    }

    public static BigDecimal cashBalance(FoldState s) {
        BigDecimal b = BigDecimal.ZERO;
        for (CashEntry c : s.cash.values()) {
            b = b.add(c.direction().equals("IN") ? c.amount() : c.amount().negate());
        }
        return MoneyMath.cash(b, s.props);
    }

    private static String cashKey(Effect eff, String category) {
        return eff.effectKey() + ":" + category;
    }

    private static Long unbox(Long v) {
        return v == null ? Long.MIN_VALUE : v;
    }

    private record LotTake(Lot lot, BigDecimal qty) {
    }
}
