package com.payflow.db;

import com.zaxxer.hikari.HikariDataSource;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Data sources (FS-14 / C5):
 * <ul>
 *   <li>{@code primaryDataSource}: Hikari pool (default 20 connections) with a short
 *       connection timeout, so an exhausted pool fails fast with 503 instead of queueing.
 *       In docker-compose the pool points at PgBouncer (transaction pooling).</li>
 *   <li>{@code replicaDataSource}: optional ({@code payflow.replica.url}); read-only
 *       transactions such as reconciliation scans are routed to it.</li>
 * </ul>
 * The application uses a {@link LazyConnectionDataSourceProxy} over a routing data source, so
 * the read-only flag of the transaction is known when the physical connection is chosen.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource primaryDataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("payflow-primary");
        return ds;
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("primaryDataSource") HikariDataSource primary, Environment env) {
        String replicaUrl = env.getProperty("payflow.replica.url");
        if (replicaUrl == null || replicaUrl.isBlank()) {
            return primary;
        }
        HikariDataSource replica = new HikariDataSource();
        replica.setPoolName("payflow-replica");
        replica.setJdbcUrl(replicaUrl);
        replica.setUsername(env.getProperty("payflow.replica.username", primary.getUsername()));
        replica.setPassword(env.getProperty("payflow.replica.password", primary.getPassword()));
        replica.setMaximumPoolSize(env.getProperty("payflow.replica.max-pool-size", Integer.class, 10));
        replica.setReadOnly(true);
        replica.setConnectionTimeout(primary.getConnectionTimeout());

        ReadReplicaRoutingDataSource routing = new ReadReplicaRoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(Route.PRIMARY, primary);
        targets.put(Route.REPLICA, replica);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(primary);
        routing.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routing);
    }

    enum Route { PRIMARY, REPLICA }

    /** Sends read-only transactions to the replica, everything else to the primary. */
    static class ReadReplicaRoutingDataSource extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? Route.REPLICA : Route.PRIMARY;
        }
    }
}
