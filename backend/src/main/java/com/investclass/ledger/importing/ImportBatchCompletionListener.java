package com.investclass.ledger.importing;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * 作业结束：把 import_batch 置为终态。新增/重复计数由 {@link CountingEventWriter}
 * 逐 chunk 直接累计到 import_batch，并发与重启安全。
 */
public class ImportBatchCompletionListener implements JobExecutionListener {

    private final ImportBatchRepository batches;

    public ImportBatchCompletionListener(ImportBatchRepository batches) {
        this.batches = batches;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        Long batchId = jobExecution.getJobParameters().getLong("batchId");
        if (batchId == null || batchId <= 0) {
            return; // DUPLICATE 文件未真正起作业
        }
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            batches.markStatus(batchId, "COMPLETED", null);
        } else {
            String err = jobExecution.getAllFailureExceptions().stream()
                    .map(Throwable::getMessage).findFirst().orElse("import failed");
            batches.markStatus(batchId, "FAILED", err);
        }
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        // no-op
    }
}
