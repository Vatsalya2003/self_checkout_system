package com.selfcheckout.self_checkout.persistence.repository;

import com.selfcheckout.self_checkout.persistence.entity.Item;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// PERSISTENCE layer (database access).
//
// Loaded LAZILY on first use, never in a constructor or @PostConstruct.
// data.sql runs while the Spring context is still starting, so an eager load
// could legitimately read an empty items table and cache nothing.
//
// After the first load this is pure memory: a scan no longer sends a SELECT to
// find an item's price, and the analytics rank stage no longer looks up names.
@Repository
public class LazyCatalogCache implements CatalogCache {

    private final ItemRepository itemRepository;

    // volatile: written once by whichever thread loads it, read by every
    // request thread afterwards. Publishing an immutable map through a
    // volatile field is enough -- no lock on the read path.
    private volatile Map<String, CatalogEntry> bySku;
    private volatile List<CatalogEntry> ordered;

    public LazyCatalogCache(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
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
        // findAllByOrderBySkuAsc, because GET /items must keep returning the
        // catalog in SKU order -- the load client treats that order as the
        // popularity rank for its sampler.
        for (Item i : itemRepository.findAllByOrderBySkuAsc()) {
            CatalogEntry e = new CatalogEntry(i.getSku(), i.getName(), i.getPrice());
            list.add(e);
            m.put(e.sku(), e);
        }
        ordered = List.copyOf(list);
        bySku = Map.copyOf(m);          // immutable once published
        return bySku;
    }
}
