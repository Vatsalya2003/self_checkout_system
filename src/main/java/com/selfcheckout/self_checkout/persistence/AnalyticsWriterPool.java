package com.selfcheckout.self_checkout.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

// PERSISTENCE layer (database access).
//
// A SECOND, tiny connection pool, used only by the analytics pipeline's
// background writers.
//
// Why this exists -- measured, not guessed. With the writers sharing the main
// 32-connection pool, a stress run reported this per snapshot write:
//
//     poolWait = 66,480us    execute = 230us    commit = 156us
//
// 99.4% of the sink's time was spent queueing for a connection behind
// checkout, which at 100 stations keeps the main pool saturated while it
// waits on item row locks. The write itself takes under half a millisecond.
//
// Two connections are enough because there are at most two writer threads
// (the sink, and the scan-log writer when raw scans are enabled) and each
// holds its connection for well under a millisecond. Postgres max_connections
// is 100 here and the main pool is 32, so 34 total is still comfortable.
//
// This is the pipes-and-filters isolation argument applied to a resource
// rather than to code: analytics already cannot block checkout on a queue, so
// it should not be able to block itself behind checkout on a pool either.
//
// Note on the shape of this class: it deliberately is NOT a @Bean of type
// DataSource. Spring Boot auto-configures the main pool only when no
// DataSource bean exists, so exposing a second one directly would switch that
// auto-configuration off and we would have to hand-wire both. Wrapping it
// keeps the main pool exactly as Boot configures it from application.properties.
@Component
public class AnalyticsWriterPool {

    private final HikariDataSource pool;

    public AnalyticsWriterPool(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password:}") String password,
            @Value("${selfcheckout.analytics.writer-pool-size:2}") int size) {

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);        // keep them open; the writers are always on
        config.setPoolName("analytics-writer-pool");
        // These writers run on background threads with nothing waiting on
        // them, so a failure should surface as a logged stage error rather
        // than a thread parked for 30 seconds.
        config.setConnectionTimeout(5_000);
        this.pool = new HikariDataSource(config);
    }

    public DataSource dataSource() {
        return pool;
    }

    // Spring destroys beans in reverse dependency order, so the pipeline
    // (which depends on the writers, which depend on this) has already
    // flushed and stopped by the time this runs.
    @PreDestroy
    public void close() {
        pool.close();
    }
}
