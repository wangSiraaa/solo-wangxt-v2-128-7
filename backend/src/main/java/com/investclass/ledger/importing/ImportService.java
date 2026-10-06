package com.investclass.ledger.importing;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 导入服务：接收 CSV -> 校验文件 sha256（重复文件直接 DUPLICATE）->
 * 启动 Spring Batch 作业（可并发，行级幂等保证同一成交/公司行动只登记一次）。
 */
@Service
public class ImportService {

    private final JobLauncher jobLauncher;
    private final Job importEventsJob;
    private final ImportBatchRepository batches;

    public ImportService(JobLauncher jobLauncher, Job importEventsJob,
                         ImportBatchRepository batches) {
        this.jobLauncher = jobLauncher;
        this.importEventsJob = importEventsJob;
        this.batches = batches;
    }

    public record Accepted(long batchId, Long jobExecutionId, String status) {
    }

    public Accepted accept(String fileName, byte[] content, String sourceSystem)
            throws Exception {
        String sha = sha256(content);
        long batchId = batches.register(fileName, sha);
        if (batchId < 0) {
            // 同一文件重复提交：不重新起作业
            return new Accepted(-batchId, null, "DUPLICATE");
        }

        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "replay-ledger-imports");
        Files.createDirectories(dir);
        Path target = dir.resolve(batchId + "-" + Math.abs(fileName.hashCode()) + ".csv");
        Files.write(target, content);

        JobParameters params = new JobParametersBuilder()
                .addLong("batchId", batchId)
                .addString("filePath", target.toString())
                .addString("sourceSystem", sourceSystem)
                .addDate("submittedAt", java.util.Date.from(Instant.now()))
                .toJobParameters();
        batches.running(batchId, null, 0);
        var execution = jobLauncher.run(importEventsJob, params);
        return new Accepted(batchId, execution.getId(), execution.getStatus().name());
    }

    static String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
