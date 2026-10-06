package com.investclass.ledger;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;

/** 整个测试 JVM 共享一个嵌入式 PostgreSQL，避免多测试类启停导致端口竞争。 */
public final class SharedEmbeddedPostgres {

    private static volatile EmbeddedPostgres pg;

    private SharedEmbeddedPostgres() {
    }

    public static synchronized EmbeddedPostgres get() {
        if (pg == null) {
            try {
                pg = EmbeddedPostgres.builder().start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        pg.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }));
            } catch (IOException e) {
                throw new IllegalStateException("failed to start embedded postgres", e);
            }
        }
        return pg;
    }
}
