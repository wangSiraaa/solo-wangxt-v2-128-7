package com.investclass.ledger.projection;

import com.investclass.ledger.core.AccountingException;
import com.investclass.ledger.core.AccountingProperties;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventType;
import com.investclass.ledger.core.MoneyMath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把不可变事件列表计划成规范顺序的效应流（纯函数，确定性）。
 *
 * 规范顺序（与导入先后、文件并发无关，只由业务事实决定）：
 *   (生效日, 阶段序号, 事件 id, effectKey)
 * 阶段：配股到账(0) → 成交结算(1) → 拆股调整(2) → 权益资格(3) → 权益支付(4)
 *
 * 四个业务日分别承担：
 *  - 交易日(businessDate)：成交成立；在途拆股按 (交易日, 结算日] 比例连乘积折算结算数量。
 *  - 除权日(businessDate of CA)：拆股在除权日改批次；分红以除权日切分含权/除权交易。
 *  - 登记日(recordDate)：按“登记日登记在册”计算权益资格。
 *  - 支付日(paymentDate)：现金真正入账；配股扣款；到账日(allotmentDate)新增成本批次。
 *  - 结算日(settlementDate)：成交股份与现金同日交割（不可提前入账）。
 */
public final class EffectPlanner {

    public static final int STAGE_ALLOT = 0;
    public static final int STAGE_SETTLE = 1;
    public static final int STAGE_SPLIT = 2;
    public static final int STAGE_ENTITLE = 3;
    public static final int STAGE_PAY = 4;

    private static final AccountingProperties P = AccountingProperties.defaults();

    private EffectPlanner() {
    }

    public record Planned(List<Effect> effects) {
    }

    public static Planned plan(List<Event> events) {
        List<Event> ordered = events.stream()
                .sorted(Comparator.comparing(Event::businessDate).thenComparing(Event::id))
                .toList();

        List<Effect> out = new ArrayList<>();
        for (Event e : ordered) {
            switch (e.type()) {
                case TRADE -> planTrade(e, ordered, out);
                case STOCK_SPLIT -> out.add(new Effect(Effect.key(e.id(), "SPLIT"), e.id(),
                        e.businessDate(), "SPLIT_ADJUST", Effect.Kind.SPLIT_ADJUST,
                        e.instrument(), MoneyMath.shares(
                        e.payload().asSplit().ratio(), P), null, null, null,
                        e.payload().asSplit().ratio(), List.of()));
                case CASH_DIVIDEND -> planDividend(e, ordered, out);
                case RIGHTS_OFFER -> planRights(e, ordered, out);
            }
        }

        out.sort(Comparator
                .comparing(Effect::effectiveDate)
                .thenComparingInt(eff -> stageOrder(eff.stage()))
                .thenComparingLong(Effect::eventId)
                .thenComparing(Effect::effectKey));
        return new Planned(out);
    }

    private static int stageOrder(String stage) {
        return switch (stage) {
            case "RIGHTS_ALLOT" -> STAGE_ALLOT;
            case "BUY_SETTLE", "SELL_SETTLE" -> STAGE_SETTLE;
            case "SPLIT_ADJUST" -> STAGE_SPLIT;
            case "DIVIDEND_ENTITLE", "RIGHTS_ENTITLE" -> STAGE_ENTITLE;
            case "DIVIDEND_PAY", "RIGHTS_PAY" -> STAGE_PAY;
            default -> 9;
        };
    }

    private static void planTrade(Event e, List<Event> all, List<Effect> out) {
        var t = e.payload().asTrade();
        if (e.settlementDate() == null) {
            throw new AccountingException(e.id(), "trade requires settlementDate");
        }
        BigDecimal pendingRatio = splitProduct(all, e.instrument(),
                e.businessDate(), e.settlementDate(), -1L);
        BigDecimal settledQty = MoneyMath.shares(t.quantity().multiply(pendingRatio), P);
        // 复权后价格，用价格精度独立核算；金额仍按原始成交金额，保证现金守恒。
        BigDecimal adjustedPrice = MoneyMath.price(
                t.price().divide(pendingRatio, P.scale().price(), RoundingMode.HALF_UP), P);
        BigDecimal gross = MoneyMath.cash(t.quantity().multiply(t.price()), P);
        BigDecimal commission = t.commission() == null ? BigDecimal.ZERO
                : MoneyMath.cash(t.commission(), P);
        if (t.isBuy()) {
            // amount = 含佣现金流出
            out.add(new Effect(Effect.key(e.id(), "BUY_SETTLE"), e.id(), e.settlementDate(),
                    "BUY_SETTLE", Effect.Kind.BUY_SETTLE, e.instrument(), settledQty,
                    gross.add(commission), adjustedPrice, commission, pendingRatio, List.of()));
        } else {
            // amount = 净现金流入（扣佣）
            out.add(new Effect(Effect.key(e.id(), "SELL_SETTLE"), e.id(), e.settlementDate(),
                    "SELL_SETTLE", Effect.Kind.SELL_SETTLE, e.instrument(), settledQty,
                    gross.subtract(commission), adjustedPrice, commission, pendingRatio,
                    List.of()));
        }
    }

    private static void planDividend(Event e, List<Event> all, List<Effect> out) {
        var d = e.payload().asDividend();
        LocalDate ex = e.businessDate();
        LocalDate record = require(e, e.recordDate(), "recordDate");
        BigDecimal eligible = eligibleQty(all, e.instrument(), ex, record, e.id());
        BigDecimal gross = MoneyMath.cash(eligible.multiply(d.amountPerShare()), P);
        out.add(new Effect(Effect.key(e.id(), "ENTITLE"), e.id(), record, "DIVIDEND_ENTITLE",
                Effect.Kind.DIVIDEND_ENTITLE, e.instrument(), eligible, gross,
                MoneyMath.price(d.amountPerShare(), P), null, BigDecimal.ONE, List.of()));
        if (e.paymentDate() != null && eligible.signum() > 0) {
            out.add(new Effect(Effect.key(e.id(), "PAY"), e.id(), e.paymentDate(),
                    "DIVIDEND_PAY", Effect.Kind.DIVIDEND_PAY, e.instrument(), eligible,
                    gross, MoneyMath.price(d.amountPerShare(), P), null, BigDecimal.ONE,
                    List.of()));
        }
    }

    private static void planRights(Event e, List<Event> all, List<Effect> out) {
        var r = e.payload().asRights();
        LocalDate record = require(e, e.recordDate(), "recordDate");
        BigDecimal eligibleShares = eligibleQty(all, e.instrument(), e.businessDate(),
                record, e.id());
        BigDecimal rights = MoneyMath.shares(eligibleShares.multiply(r.rightsPerShare()), P);
        BigDecimal subscribed = r.subscribedQty() == null ? BigDecimal.ZERO
                : MoneyMath.shares(r.subscribedQty(), P);
        if (subscribed.compareTo(rights) > 0) {
            throw new AccountingException(e.id(),
                    "subscribedQty " + subscribed + " exceeds eligible rights " + rights);
        }
        BigDecimal pay = MoneyMath.cash(subscribed.multiply(r.subscriptionPrice()), P);
        out.add(new Effect(Effect.key(e.id(), "ENTITLE"), e.id(), record, "RIGHTS_ENTITLE",
                Effect.Kind.RIGHTS_ENTITLE, e.instrument(), rights, pay,
                MoneyMath.price(r.rightsPerShare(), P), null, BigDecimal.ONE, List.of()));
        // 即使不认购，支付阶段效应也存在：把资格置为 EXPIRED（部分认购则 PARTIALLY_SUBSCRIBED）
        if (e.paymentDate() != null) {
            out.add(new Effect(Effect.key(e.id(), "PAY"), e.id(), e.paymentDate(),
                    "RIGHTS_PAY", Effect.Kind.RIGHTS_PAY, e.instrument(), subscribed, pay,
                    MoneyMath.price(r.subscriptionPrice(), P), null, BigDecimal.ONE, List.of()));
        }
        if (e.allotmentDate() != null && subscribed.signum() > 0) {
            out.add(new Effect(Effect.key(e.id(), "ALLOT"), e.id(), e.allotmentDate(),
                    "RIGHTS_ALLOT", Effect.Kind.RIGHTS_ALLOT, e.instrument(), subscribed,
                    pay, MoneyMath.price(r.subscriptionPrice(), P), null, BigDecimal.ONE,
                    List.of()));
        }
    }

    /**
     * 登记日资格股数（登记日在册口径，当前单位）：
     *  - 成交必须在登记日（含）前结算交割：settleDate &lt;= recordDate；
     *  - 买入为正、卖出为负；除权日之前成交的是含权交易，除权日（含）之后成交为除权交易；
     *  - 再按 (成交日, 登记日] 区间拆股连乘积折算到当前单位；
     *  - 加上到账日 &lt;= 登记日的配股新增批次。
     */
    static BigDecimal eligibleQty(List<Event> all, String instrument,
                                  LocalDate exDate, LocalDate recordDate, long caEventId) {
        BigDecimal q = BigDecimal.ZERO;
        for (Event t : all) {
            if (t.type() != EventType.TRADE || !t.instrument().equals(instrument)) {
                continue;
            }
            if (t.settlementDate() == null || t.settlementDate().isAfter(recordDate)) {
                continue; // 登记日时尚未交割，不影响在册数量
            }
            var trade = t.payload().asTrade();
            BigDecimal signed = trade.isBuy() ? trade.quantity() : trade.quantity().negate();
            BigDecimal ratio = splitProduct(all, instrument,
                    t.businessDate(), recordDate, -1L);
            q = q.add(MoneyMath.shares(signed.multiply(ratio), P));
        }
        for (Event c : all) {
            if (c.type() == EventType.RIGHTS_OFFER && c.instrument().equals(instrument)
                    && c.allotmentDate() != null && !c.allotmentDate().isAfter(recordDate)
                    && c.id() < caEventId) {
                var rr = c.payload().asRights();
                if (rr.subscribedQty() != null && rr.subscribedQty().signum() > 0) {
                    BigDecimal ratio = splitProduct(all, instrument,
                            c.allotmentDate(), recordDate, -1L);
                    q = q.add(MoneyMath.shares(rr.subscribedQty().multiply(ratio), P));
                }
            }
        }
        if (q.signum() < 0) {
            throw new AccountingException(caEventId,
                    "negative eligible quantity " + q + " on record date " + recordDate);
        }
        return MoneyMath.shares(q, P);
    }

    /** (afterDate, upToDate] 区间内同一证券拆股比例的连乘积。 */
    static BigDecimal splitProduct(List<Event> all, String instrument,
                                   LocalDate afterDate, LocalDate upToDate,
                                   long excludeEventId) {
        BigDecimal p = BigDecimal.ONE;
        for (Event s : all) {
            if (s.type() == EventType.STOCK_SPLIT && s.instrument().equals(instrument)) {
                LocalDate ex = s.businessDate();
                if (ex.isAfter(afterDate) && !ex.isAfter(upToDate) && s.id() != excludeEventId) {
                    p = p.multiply(s.payload().asSplit().ratio());
                }
            }
        }
        return p;
    }

    private static LocalDate require(Event e, LocalDate v, String field) {
        if (v == null) {
            throw new AccountingException(e.id(), field + " is required");
        }
        return v;
    }
}
