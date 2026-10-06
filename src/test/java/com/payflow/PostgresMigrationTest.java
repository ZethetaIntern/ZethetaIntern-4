package com.payflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "PAYFLOW_DB_URL=jdbc:h2:mem:postgres-profile-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "PAYFLOW_DB_USER=sa",
        "PAYFLOW_DB_PASSWORD=test",
        "PAYFLOW_API_KEY=test-api-key",
        "PAYFLOW_WEBHOOK_SECRET=test-webhook-secret",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ActiveProfiles("postgres")
class PostgresMigrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void postgresProfileAppliesMigrationAndValidatesEntityMappings() {
        Integer tables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, Integer.class);
        assertEquals(13, tables);
    }
}
