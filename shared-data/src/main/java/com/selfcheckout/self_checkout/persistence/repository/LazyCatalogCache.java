package com.selfcheckout.self_checkout.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// SHARED-DATA library (persistence layer, read-only).
//
// The catalog -- sku, name, price -- read once from the shared database and
// then served from memory. Three services need it: catalog-service answers
// GET /items with it, checkout-service prices every scan with it, and
// analytics-service attaches display names to the ranking with it. Sharing
// this one read-only class is how they share that piece of database access
// logic without calling each other.
//
// Week 4 change: this used to go through the JPA Item entity. It now reads the
// three columns it needs with one plain SQL query, so the shared library has no
// entity and no write method at all -- nothing in here CAN change a row.
//
// Loaded LAZILY on first use, never in a constructor or @PostConstruct, so a
// service can start before the database has been seeded and still pick the
// catalog up on its first request.
@Repository
public class LazyCatalogCache implements CatalogCache {

    // ORDER BY sku, because GET /items must keep returning the catalog in SKU
    // order -- the load client treats that order as the popularity rank for
    // its sampler.
    private static final String LOAD_SQL = "SELECT sku, name, price FROM items ORDER BY sku";

    private final JdbcTemplate jdbc;

    // volatile: written once by whichever thread loads it, read by every
    // request thread afterwards. Publishing an immutable map through a
    // volatile field is enough -- no lock on the read path.
    private volatile Map<String, CatalogEntry> bySku;
    private volatile List<CatalogEntry> ordered;

    public LazyCatalogCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CatalogEntry find(String sku) {
        if (sku == null) {
            return null;
        }
        Map<String, CatalogEntry> cache = bySku;
        return (cache != null ? cache : load()).get(sku);
    }

    @Override
    public String nameOf(String sku) {
        CatalogEntry e = find(sku);
        return e != null ? e.name() : sku;
    }

    @Override
    public List<CatalogEntry> all() {
        if (bySku == null) {
            load();
        }
        return ordered;
    }

    private synchronized Map<String, CatalogEntry> load() {
        if (bySku != null) {            // another thread got here first
            return bySku;
        }
        List<CatalogEntry> list = new ArrayList<>();
        Map<String, CatalogEntry> m = new HashMap<>();
        jdbc.query(LOAD_SQL, rs -> {
            CatalogEntry e = new CatalogEntry(rs.getString("sku"), rs.getString("name"),
                                              rs.getBigDecimal("price"));
            list.add(e);
            m.put(e.sku(), e);
        });
        ordered = List.copyOf(list);
        bySku = Map.copyOf(m);          // immutable once published
        return bySku;
    }
}
