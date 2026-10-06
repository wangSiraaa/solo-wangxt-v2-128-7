package com.investclass.ledger.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investclass.ledger.ledger.EventRepository;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.PathResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch 导入作业：CSV 行 -> 规范化事件 -> 幂等登记到不可变账本。
 *
 * 并发/重复三道闸：
 *  1. 文件级：import_batch.sha256 唯一（重复文件标 DUPLICATE）。
 *  2. 行级：business_event (source_system, source_key) 唯一 + ON CONFLICT DO NOTHING。
 *  3. 凭证级：idempotency_key = SHA-256(来源系统|规范化业务指纹)，
 *     文件名不同但业务事实相同也识别为同一成交/公司行动。
 * 计数通过 StepExecution 执行上下文，两个并发作业互不干扰。
 */
@Configuration
public class ImportBatchConfig {

    public static final String[] COLUMNS = {
            "rowType", "accountId", "instrument", "businessDate", "settlementDate",
            "recordDate", "paymentDate", "allotmentDate", "side", "quantity", "price",
            "commission", "ratio", "amountPerShare", "rightsPerShare", "subscriptionPrice",
            "subscribedQty", "currency", "sourceKey"
    };

    @Bean
    @StepScope
    public FlatFileItemReader<ImportRow> importRowReader(
            @Value("#{jobParameters['filePath']}") String filePath) {
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames(COLUMNS);

        DefaultLineMapper<ImportRow> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper((FieldSet fs) -> new ImportRow(
                s(fs, "rowType"), s(fs, "accountId"), s(fs, "instrument"),
                s(fs, "businessDate"), s(fs, "settlementDate"), s(fs, "recordDate"),
                s(fs, "paymentDate"), s(fs, "allotmentDate"), s(fs, "side"),
                s(fs, "quantity"), s(fs, "price"), s(fs, "commission"),
                s(fs, "ratio"), s(fs, "amountPerShare"), s(fs, "rightsPerShare"),
                s(fs, "subscriptionPrice"), s(fs, "subscribedQty"),
                s(fs, "currency"), s(fs, "sourceKey")));

        return new FlatFileItemReaderBuilder<ImportRow>()
                .name("importRowReader")
                .resource(new PathResource(filePath))
                .linesToSkip(1)
                .lineMapper(lineMapper)
                .strict(true)
                .build();
    }

    private static String s(FieldSet fs, String name) {
        String v = fs.readString(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    @Bean
    @StepScope
    public ItemProcessor<ImportRow, Registration> importRowProcessor(
            ObjectMapper mapper,
            @Value("#{jobParameters['sourceSystem']}") String sourceSystem) {
        return row -> {
            var event = row.toEvent(sourceSystem);
            String json = mapper.writeValueAsString(event.payload());
            String idemKey = Idempotency.sha256Hex(
                    sourceSystem + "|" + event.canonicalFingerprint());
            return new Registration(row, sourceSystem, json, idemKey);
        };
    }

    @Bean
    @org.springframework.batch.core.configuration.annotation.StepScope
    public ItemWriter<Registration> eventWriter(EventRepository eventRepository,
                                                org.springframework.jdbc.core.JdbcTemplate jdbc,
                                                @Value("#{jobParameters['batchId']}") long batchId) {
        return new CountingEventWriter(eventRepository, jdbc, batchId);
    }

    @Bean
    public ImportBatchCompletionListener completionListener(ImportBatchRepository batches) {
        return new ImportBatchCompletionListener(batches);
    }

    @Bean
    public Job importEventsJob(JobRepository repo, PlatformTransactionManager tx,
                               FlatFileItemReader<ImportRow> importRowReader,
                               ItemProcessor<ImportRow, Registration> importRowProcessor,
                               ItemWriter<Registration> eventWriter,
                               ImportBatchCompletionListener completionListener) {
        Step step = new StepBuilder("import-events", repo)
                .<ImportRow, Registration>chunk(50, tx)
                .reader(importRowReader)
                .processor(importRowProcessor)
                .writer(eventWriter)
                .build();
        return new JobBuilder("importEventsJob", repo)
                .start(step)
                .listener(completionListener)
                .build();
    }
}
