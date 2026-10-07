package com.payflow.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One real PostgreSQL 15 server for the whole test JVM (zonky embedded-postgres,
 * no Docker required). Flyway migrates it on context start-up, so every test
 * also proves the migrations and the entity mappings ({@code ddl-auto=validate}).
 */
public final class TestDatabase {

    private static EmbeddedPostgres postgres;

    private TestDatabase() {}

    public static synchronized String jdbcUrl() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot start embedded PostgreSQL", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // JVM is exiting
                }
            }));
        }
        return postgres.getJdbcUrl("postgres", "postgres");
    }
}
