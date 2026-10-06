package com.investclass.ledger;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 真实嵌入式 PostgreSQL 端到端测试基类。
 * schema.sql（不可变事件账本 + 投影 + 快照）由 Spring SQL init 自动执行。
 * 多个测试类共享同一个嵌入式实例。
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
public abstract class AbstractIntegrationTest {

    @BeforeAll
    static void startPg() {
        SharedEmbeddedPostgres.get();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        String jdbcUrl = SharedEmbeddedPostgres.get().getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }
}
