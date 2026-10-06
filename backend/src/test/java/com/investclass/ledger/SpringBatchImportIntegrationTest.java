package com.investclass.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验收：两个导入批次（同一文件并发/重复提交）包含同一成交和同一公司行动，
 * 通过文件 sha256、来源键与幂等凭证三层保证只登记一次。
 */
class SpringBatchImportIntegrationTest extends AbstractIntegrationTest {

    @Autowired private com.investclass.ledger.importing.ImportService imports;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String HEADER = String.join(",",
            "rowType", "accountId", "instrument", "businessDate", "settlementDate",
            "recordDate", "paymentDate", "allotmentDate", "side", "quantity", "price",
            "commission", "ratio", "amountPerShare", "rightsPerShare", "subscriptionPrice",
            "subscribedQty", "currency", "sourceKey");

    private static final String CSV = HEADER + "\n"
            + "TRADE,BAT1,AAA,2026-09-23,2026-09-24,,,,BUY,100,10.00,1,,,,,,CNY,BAT-T1\n"
            + "STOCK_SPLIT,BAT1,AAA,2026-10-05,,,,,,,,,3,,,,,CNY,BAT-S1\n";

    @Test
    void duplicateAndConcurrentImportRegistersEventsOnce() throws Exception {
        byte[] content = CSV.getBytes();
        Path f = Files.createTempFile("dup", ".csv");
        Files.write(f, content);

        // 同一文件重复提交：第二次直接 DUPLICATE，不重启作业
        var first = imports.accept("batch-a.csv", content, "broker-A");
        var repeat = imports.accept("batch-a.csv", content, "broker-A");
        assertThat(first.status()).isNotEqualTo("DUPLICATE");
        assertThat(repeat.status()).isEqualTo("DUPLICATE");

        // 不同文件名、不同字节（数量字段多两个空格，解析后业务事实相同）：
        // 文件级 sha 不同会启动作业，行级来源键唯一约束使两行全部判为重复、不产生新事件。
        byte[] sameFactsDifferentBytes = CSV.replace("100,10.00", "100  ,10.00")
                .getBytes("UTF-8");
        var anotherFile = imports.accept("batch-b-renamed.csv", sameFactsDifferentBytes,
                "broker-A");
        assertThat(anotherFile.batchId()).isPositive();

        Long tradeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM business_event WHERE account_id='BAT1'", Long.class);
        assertThat(tradeCount).isEqualTo(2L);

        Long distinctIdem = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT idempotency_key) FROM business_event WHERE account_id='BAT1'",
                Long.class);
        assertThat(distinctIdem).isEqualTo(2L);

        var batchRow = jdbcTemplate.queryForMap(
                "SELECT inserted_events, duplicate_rows, status FROM import_batch "
                        + "WHERE id=?", anotherFile.batchId());
        assertThat(((Number) batchRow.get("duplicate_rows")).intValue()).isEqualTo(2);
        assertThat(((Number) batchRow.get("inserted_events")).intValue()).isZero();
    }
}
