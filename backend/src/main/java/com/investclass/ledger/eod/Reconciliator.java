package com.investclass.ledger.eod;

import com.investclass.ledger.core.AccountingProperties;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.MoneyMath;
import com.investclass.ledger.projection.Effect;
import com.investclass.ledger.projection.EffectPlanner;
import com.investclass.ledger.projection.FoldEngine;
import com.investclass.ledger.projection.FoldState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布前三方守恒 + 账实核对（纯函数，可离线用于验收测试）：
 *  1) 数量守恒：批次剩余总量 == 按效应重算的在册总量（整股与零碎股分开）。
 *  2) 现金守恒：现金行净额 == 按效应重算的现金净额；现金精度独立。
 *  3) 批次成本守恒：
 *       买入/认购总成本 = 剩余 openCost + 卖出已释放 costReleased（精确到分）。
 *  4) 账实核对：投影持仓/现金与外部对账单逐券相符（外部数据仅用于核对，绝不反写历史）。
 *
 * 任一不平衡：报告 earliestMismatchEventId（按规范顺序最早的失配事件）与明细，
 * 阻止发布。
 */
public final class Reconciliator {

    private Reconciliator() {
    }

    public record ExternalStatement(String instrument, LocalDate businessDate,
                                    BigDecimal qty, BigDecimal fractionalQty,
                                    BigDecimal cash, BigDecimal cost) {
    }

    public static ReconciliationReport reconcile(String accountId, LocalDate businessDate,
                                                 long watermark, List<Event> events,
                                                 List<ExternalStatement> external,
                                                 AccountingProperties props) {
        FoldState s = new FoldState(props);
        Map<Long, Event> byId = new LinkedHashMap<>();
        for (Event e : events) {
            byId.put(e.id(), e);
        }
        List<Effect> effects = EffectPlanner.plan(events).effects();

        // 逐效应折叠并在每个检查点验证：一旦失配，该效应的事件就是最早失配事件。
        long earliest = -1L;
        String detail = null;

        for (Effect eff : effects) {
            FoldEngine.apply(s, eff, byId.get(eff.eventId()), accountId);
            String problem = checkConservationAt(s, eff, props);
            if (problem != null && earliest < 0) {
                earliest = eff.eventId();
                detail = "at effect " + eff.effectKey() + " (" + eff.effectiveDate() + "): "
                        + problem;
            }
        }

        // 终点总量（按批次）
        List<FoldEngine.PositionView> positions = FoldEngine.positions(s);

        // 独立重算：不看批次，只按效应重放总数量/现金/成本（第二套独立口径）
        IndependentTotals ind = recomputeIndependently(effects, s, props);

        boolean qtyOk = true;
        boolean costOk = true;
        for (FoldEngine.PositionView p : positions) {
            IndependentTotals.PerInstrument pi = ind.perInstrument.get(p.instrument());
            BigDecimal expectQty = pi == null ? BigDecimal.ZERO : pi.qty;
            BigDecimal expectFrac = pi == null ? BigDecimal.ZERO : pi.fractionalQty;
            if (p.qty().compareTo(expectQty) != 0 || p.fractionalQty().compareTo(expectFrac) != 0) {
                qtyOk = false;
                if (earliest < 0) {
                    earliest = earliestTradeEvent(events, p.instrument());
                    detail = "quantity mismatch on " + p.instrument()
                            + " projected=[" + p.qty() + "+" + p.fractionalQty()
                            + "] recomputed=[" + expectQty + "+" + expectFrac + "]";
                }
            }
            BigDecimal expectCost = pi == null ? BigDecimal.ZERO : pi.openCost();
            if (p.openCost().compareTo(expectCost) != 0) {
                costOk = false;
                if (earliest < 0) {
                    earliest = earliestTradeEvent(events, p.instrument());
                    detail = "lot cost mismatch on " + p.instrument()
                            + " projected=" + p.openCost()
                            + " but invested-released=" + expectCost;
                }
            }
        }
        // 独立口径有而批次没有的证券（数量为 0 时 positions 不展示）
        for (var e : ind.perInstrument.entrySet()) {
            boolean exists = positions.stream().anyMatch(p -> p.instrument().equals(e.getKey()));
            IndependentTotals.PerInstrument pi = e.getValue();
            if (!exists && (pi.qty.signum() != 0 || pi.fractionalQty.signum() != 0
                    || pi.openCost().signum() != 0)) {
                qtyOk = false;
                costOk = false;
                if (earliest < 0) {
                    earliest = earliestTradeEvent(events, e.getKey());
                    detail = "missing lot projection for " + e.getKey();
                }
            }
        }

        boolean cashOk = FoldEngine.cashBalance(s).compareTo(ind.cash) == 0;
        if (!cashOk && earliest < 0) {
            earliest = events.stream().mapToLong(Event::id).min().orElse(-1);
            detail = "cash mismatch projected=" + FoldEngine.cashBalance(s)
                    + " recomputed=" + ind.cash;
        }

        // 账实核对
        BigDecimal projectedCash = FoldEngine.cashBalance(s);
        BigDecimal externalCash = external.stream().map(ExternalStatement::cash)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        boolean externalOk = true;
        List<ReconciliationReport.Line> lines = new ArrayList<>();
        Map<String, FoldEngine.PositionView> posByInstrument = new LinkedHashMap<>();
        for (FoldEngine.PositionView p : positions) {
            posByInstrument.put(p.instrument(), p);
        }
        for (ExternalStatement st : external) {
            FoldEngine.PositionView p = posByInstrument.get(st.instrument());
            BigDecimal pq = p == null ? BigDecimal.ZERO : p.qty();
            BigDecimal pf = p == null ? BigDecimal.ZERO : p.fractionalQty();
            BigDecimal pc = p == null ? BigDecimal.ZERO : p.openCost();
            BigDecimal eq = st.qty() == null ? BigDecimal.ZERO : st.qty();
            BigDecimal ef = st.fractionalQty() == null ? BigDecimal.ZERO : st.fractionalQty();
            BigDecimal ec = st.cost() == null ? BigDecimal.ZERO : st.cost();
            boolean qm = pq.compareTo(eq) == 0 && pf.compareTo(ef) == 0;
            boolean cm = pc.compareTo(ec) == 0;
            if (!qm || !cm) {
                externalOk = false;
                if (earliest < 0) {
                    earliest = earliestTradeEvent(events, st.instrument());
                    detail = "book vs external mismatch on " + st.instrument()
                            + " book=[" + pq + "+" + pf + "@" + pc + "]"
                            + " external=[" + eq + "+" + ef + "@" + ec + "]";
                }
            }
            lines.add(new ReconciliationReport.Line(st.instrument(), pq, pf, pc, eq, ef, ec,
                    st.cash(), qm, cm));
        }
        if (externalCash != null && projectedCash.compareTo(externalCash) != 0) {
            externalOk = false;
            if (earliest < 0) {
                earliest = events.stream().mapToLong(Event::id).min().orElse(-1);
                detail = "book cash " + projectedCash + " != external cash " + externalCash;
            }
        }

        return new ReconciliationReport(accountId, businessDate, watermark, qtyOk, cashOk,
                costOk, externalOk, earliest < 0 ? null : earliest, detail, lines,
                projectedCash, externalCash);
    }

    /** 每个检查点都验证成本不串批：剩余成本不得为负、不得超过总成本。 */
    private static String checkConservationAt(FoldState s, Effect eff,
                                              AccountingProperties props) {
        for (var l : s.lots.values()) {
            if (l.remainingCost().signum() < 0) {
                return "lot " + l.lotKey() + " negative remaining cost " + l.remainingCost();
            }
            if (l.remainingCost().compareTo(l.totalCost()) > 0) {
                return "lot " + l.lotKey() + " remaining cost " + l.remainingCost()
                        + " exceeds total " + l.totalCost();
            }
            if (l.remainingQty().signum() < 0) {
                return "lot " + l.lotKey() + " negative remaining qty " + l.remainingQty();
            }
        }
        return null;
    }

    private static long earliestTradeEvent(List<Event> events, String instrument) {
        return events.stream()
                .filter(e -> e.instrument().equals(instrument))
                .mapToLong(Event::id).min().orElse(-1);
    }

    /** 与批次折叠完全独立的第二套口径：只按效应累计总量/现金/成本，模拟外部重算。 */
    static class IndependentTotals {
        static class PerInstrument {
            BigDecimal qty = BigDecimal.ZERO;
            BigDecimal fractionalQty = BigDecimal.ZERO;
            /** 投入成本（买入含佣 + 配股认购），拆股不改变投入；成本守恒起点。 */
            BigDecimal investedCost = BigDecimal.ZERO;
            /** FIFO 已释放成本（来自折叠产生的结转，按证券汇总）。 */
            BigDecimal releasedCost = BigDecimal.ZERO;

            BigDecimal openCost() {
                return MoneyMath.cash(investedCost.subtract(releasedCost),
                        AccountingProperties.defaults());
            }
        }

        final Map<String, PerInstrument> perInstrument = new LinkedHashMap<>();
        BigDecimal cash = BigDecimal.ZERO;

        PerInstrument of(String i) {
            return perInstrument.computeIfAbsent(i, k -> new PerInstrument());
        }
    }

    private static IndependentTotals recomputeIndependently(List<Effect> effects, FoldState s,
                                                            AccountingProperties props) {
        IndependentTotals t = new IndependentTotals();
        for (Effect eff : effects) {
            IndependentTotals.PerInstrument pi = t.of(eff.instrument());
            switch (eff.kind()) {
                case BUY_SETTLE -> {
                    BigDecimal cost = MoneyMath.cash(eff.amount(), props);
                    BigDecimal whole = eff.qty().setScale(0, RoundingMode.FLOOR);
                    BigDecimal frac = MoneyMath.shares(eff.qty().subtract(whole), props);
                    pi.qty = pi.qty.add(whole);
                    pi.fractionalQty = MoneyMath.shares(pi.fractionalQty.add(frac), props);
                    pi.investedCost = MoneyMath.cash(pi.investedCost.add(cost), props);
                    t.cash = MoneyMath.cash(t.cash.subtract(cost), props);
                }
                case SELL_SETTLE -> {
                    BigDecimal fee = eff.fee() == null ? BigDecimal.ZERO : eff.fee();
                    BigDecimal grossIn = MoneyMath.cash(eff.amount().add(fee), props);
                    t.cash = MoneyMath.cash(t.cash.add(grossIn).subtract(fee), props);
                    BigDecimal total = pi.qty.add(pi.fractionalQty);
                    BigDecimal left = MoneyMath.shares(total.subtract(eff.qty()), props);
                    BigDecimal leftWhole = left.setScale(0, RoundingMode.FLOOR);
                    pi.qty = leftWhole;
                    pi.fractionalQty = MoneyMath.shares(left.subtract(leftWhole), props);
                }
                case SPLIT_ADJUST -> {
                    BigDecimal ratio = eff.qty();
                    BigDecimal total = MoneyMath.shares(
                            pi.qty.add(pi.fractionalQty).multiply(ratio), props);
                    BigDecimal whole = total.setScale(0, RoundingMode.FLOOR);
                    pi.qty = whole;
                    pi.fractionalQty = MoneyMath.shares(total.subtract(whole), props);
                    // 拆股只切数量，不改变投入成本
                }
                case RIGHTS_ALLOT -> {
                    BigDecimal whole = eff.qty().setScale(0, RoundingMode.FLOOR);
                    BigDecimal frac = MoneyMath.shares(eff.qty().subtract(whole), props);
                    pi.qty = pi.qty.add(whole);
                    pi.fractionalQty = MoneyMath.shares(pi.fractionalQty.add(frac), props);
                    pi.investedCost = MoneyMath.cash(pi.investedCost.add(eff.amount()), props);
                }
                case RIGHTS_PAY -> {
                    if (eff.amount().signum() > 0) {
                        t.cash = MoneyMath.cash(t.cash.subtract(eff.amount()), props);
                    }
                }
                case DIVIDEND_PAY -> t.cash = MoneyMath.cash(t.cash.add(eff.amount()), props);
                default -> {
                    // 资格阶段不动现金/股份
                }
            }
        }
        // 已释放成本独立汇总自结转行（FIFO 口径产出），形成“投入 = 剩余 + 释放”恒等式
        for (var c : s.consumptions.values()) {
            t.of(c.instrument()).releasedCost = MoneyMath.cash(
                    t.of(c.instrument()).releasedCost.add(c.costReleased()), props);
        }
        return t;
    }
}
