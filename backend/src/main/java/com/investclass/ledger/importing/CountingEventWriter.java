package com.investclass.ledger.importing;

import com.investclass.ledger.ledger.EventRepository;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 登记写入器：逐行幂等插入，并把“新增/重复”计数直接累计到 import_batch 行。
 * 计数不依赖 StepExecutionContext，使 @StepScope 代理 / 并发作业 / 重启都互不干扰。
 */
@StepScope
public class CountingEventWriter implements ItemWriter<Registration> {

    private final EventRepository eventRepository;
    private final JdbcTemplate jdbc;
    private final long batchId;

    public CountingEventWriter(EventRepository eventRepository, JdbcTemplate jdbc,
                               @Value("#{jobParameters['batchId']}") long batchId) {
        this.eventRepository = eventRepository;
        this.jdbc = jdbc;
        this.batchId = batchId;
    }

    @Override
    public void write(Chunk<? extends Registration> chunk) {
        int inserted = 0;
        int duplicates = 0;
        for (Registration reg : chunk) {
            var event = reg.row().toEvent(reg.sourceSystem());
            var result = eventRepository.insertIfAbsent(event, reg.payloadJson(),
                    reg.idempotencyKey());
            if (result.duplicate()) {
                duplicates++;
            } else {
                inserted++;
            }
        }
        if (inserted > 0) {
            jdbc.update(
                    "UPDATE import_batch SET inserted_events = inserted_events + ? WHERE id=?",
                    inserted, batchId);
        }
        if (duplicates > 0) {
            jdbc.update(
                    "UPDATE import_batch SET duplicate_rows = duplicate_rows + ? WHERE id=?",
                    duplicates, batchId);
        }
    }
}
