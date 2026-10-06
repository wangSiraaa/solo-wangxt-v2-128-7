package com.investclass.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.ledger.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等与不可变账本验收：
 *  - 来源键 + 幂等凭证使重复/并发的同一成交只登记一次；
 *  - business_event 触发器禁止 UPDATE/DELETE（快照不能反向补造历史）。
 */
class LedgerImmutabilityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EventRepository events;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    private Event trade(String key) {
        return new Event(null, com.investclass.ledger.core.EventType.TRADE, "A9", "AAA",
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"), null, null,
                null, new com.investclass.ledger.core.EventPayload.Trade("BUY",
                        new BigDecimal("100"), new BigDecimal("10.00"),
                        new BigDecimal("1"), "CNY"),
                "broker", key, null, false, null);
    }

    @Test
    void sameTradeFromTwoConcurrentBatchesRegisteredOnce() throws Exception {
        Event e = trade("T-20260901-AAA-100");
        String json = mapper.writeValueAsString(e.payload());
        String idem = "idem-key-1";

        var first = events.insertIfAbsent(e, json, idem);
        assertThat(first.duplicate()).isFalse();

        // 同 (source_system, source_key) 再来一次（第二个并发批次的同一成交）
        var second = events.insertIfAbsent(e, json, idem);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.event().id()).isEqualTo(first.event().id());

        Long count = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM business_event WHERE source_key = ?",
                Long.class, "T-20260901-AAA-100");
        assertThat(count).isEqualTo(1L);

        // 不同来源键但同一幂等凭证（不同文件名、规范化后同一事实）也拒绝
        Event otherKey = trade("a-different-source-key");
        assertThatThrownBy(() -> events.insertIfAbsent(otherKey, json, idem))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void ledgerIsAppendOnlyNoUpdateNoDelete() {
        Event e = trade("IMMUTABLE-1");
        String json;
        try {
            json = mapper.writeValueAsString(e.payload());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        var saved = events.insertIfAbsent(e, json, "idem-immutable").event();

        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update(
                "UPDATE business_event SET instrument='BBB' WHERE id=?", saved.id()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update(
                "DELETE FROM business_event WHERE id=?", saved.id()))
                .hasMessageContaining("append-only");
    }
}
