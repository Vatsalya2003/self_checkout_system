package com.selfcheckout.self_checkout.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

// CHECKOUT-SERVICE, persistence layer.
//
// A SECOND, tiny connection pool, used only by the scan-log writer thread
// (ScanLogPublisher) to append scans to scan_log. Week 3 called this the
// AnalyticsWriterPool, because it served the in-process analytics writers;
// since Week 4 the only writer left on it in this service is the scan log.
//
// Why a separate pool -- measured, not guessed. In Week 3, a background writer
// sharing the main 32-connection pool spent 66,480 us per write waiting for a
// connection (execute 230 us, commit 156 us): at 100 stations checkout keeps
// the main pool saturated while it waits on item row locks, and the writer sat
// at the back of that queue. With its own pool the wait was 11 us. The same
// reasoning applies to the scan-log writer now, which runs ~10 inserts a
// second of up to 1,000 rows each at stress rates.
//
// Two connections: there is one writer thread, and the second covers the
// startup MAX(seq) read and a reconnect. Postgres max_connections is 100; this
// service uses 32 + 2 of them (see SERVICES.md for the whole budget).
//
// Deliberately NOT a @Bean of type DataSource: Spring Boot auto-configures the
// main pool only when no DataSource bean exists, so exposing a second one
// directly would switch that off and we would have to hand-wire both.
@Component
public class ScanLogWriterPool {

    private final HikariDataSource pool;

    public ScanLogWriterPool(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password:}") String password,
            @Value("${selfcheckout.scan-log.writer-pool-size:2}") int size) {

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);        // keep them open; the writer is always on
        config.setPoolName("scan-log-writer-pool");
        // The writer runs on a background thread with nothing waiting on it,
        // so a failure should surface as a logged retry rather than a thread
        // parked for 30 seconds.
        config.setConnectionTimeout(5_000);
        this.pool = new HikariDataSource(config);
    }

    public DataSource dataSource() {
        return pool;
    }

    // Spring destroys beans in reverse dependency order, so the publisher
    // (which depends on the writer, which depends on this) has already
    // flushed and stopped by the time this runs.
    @PreDestroy
    public void close() {
        pool.close();
    }
}
