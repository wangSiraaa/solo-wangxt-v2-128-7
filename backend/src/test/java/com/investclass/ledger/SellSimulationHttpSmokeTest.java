package com.investclass.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.core.Event;
import com.investclass.ledger.core.EventPayload;
import com.investclass.ledger.core.EventType;
import com.investclass.ledger.importing.Idempotency;
import com.investclass.ledger.ledger.EventRepository;
import com.investclass.ledger.projection.ProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 层冒烟：试算端点的 JSON 序列化/反序列化与非法输入 400。 */
@AutoConfigureMockMvc
class SellSimulationHttpSmokeTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private EventRepository events;
    @Autowired private ProjectionService projection;
    @Autowired private ObjectMapper mapper;

    @Test
    void simulateSellEndpointRoundTrip() throws Exception {
        String acc = "SIMHTTP";
        Event buy = new Event(null, EventType.TRADE, acc, "EEE",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-02"),
                null, null, null,
                new EventPayload.Trade("BUY", new BigDecimal("100"),
                        new BigDecimal("10"), BigDecimal.ZERO, "CNY"),
                "file", "SIMHTTP-T1", null, false, null);
        String json = mapper.writeValueAsString(buy.payload());
        events.insertIfAbsent(buy, json,
                Idempotency.sha256Hex("file|" + buy.canonicalFingerprint()));
        projection.projectTo(acc, events.maxEventId(acc), true);

        mvc.perform(post("/api/accounts/{a}/sell-simulation", acc)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrument":"EEE","tradeDate":"2026-08-03",
                                 "settlementDate":"2026-08-04","quantity":40,"price":12.5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booked").value(false))
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.availableQty").value(100))
                .andExpect(jsonPath("$.costReleased").value(400.00))
                .andExpect(jsonPath("$.proceeds").value(500.00))
                .andExpect(jsonPath("$.expectedPnl").value(100.00))
                .andExpect(jsonPath("$.takes[0].lotKey").exists())
                .andExpect(jsonPath("$.positionsAfter[0].qty").value(60));

        // 非法输入 -> 400
        mvc.perform(post("/api/accounts/{a}/sell-simulation", acc)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrument":"EEE","tradeDate":"2026-08-03",
                                 "settlementDate":"2026-08-04","quantity":-1,"price":12.5}
                                """))
                .andExpect(status().isBadRequest());
    }
}
