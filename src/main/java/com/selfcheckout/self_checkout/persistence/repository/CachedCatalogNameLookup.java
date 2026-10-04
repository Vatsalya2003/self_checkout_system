package com.selfcheckout.self_checkout.persistence.repository;

import com.selfcheckout.self_checkout.persistence.entity.Item;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

// PERSISTENCE layer (database access).
//
// The catalog is fixed for the life of a run (2000 items, seeded by data.sql
// at boot and never changed), so the names are loaded once and kept.
//
// Loaded LAZILY, not in a @PostConstruct. data.sql runs while the Spring
// context is still starting, so a constructor or @PostConstruct read could
// legitimately find an empty items table. The first lookup happens when the
// first window is ranked -- at least 500 scans in, long after startup.
@Repository
public class CachedCatalogNameLookup implements CatalogNameLookup {

    private final ItemRepository itemRepository;
    private volatile Map<String, String> namesBySku;   // volatile: written by one
                                                       // thread, read by another

    public CachedCatalogNameLookup(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    @Override
    public String nameOf(String sku) {
        Map<String, String> cache = namesBySku;
        if (cache == null) {
            cache = load();
        }
        // An unknown SKU cannot normally happen (scan_log has a foreign key to
        // items), but fall back to the SKU rather than returning null.
        return cache.getOrDefault(sku, sku);
    }

    private synchronized Map<String, String> load() {
        if (namesBySku != null) {          // another thread beat us to it
            return namesBySku;
        }
        Map<String, String> m = new HashMap<>();
        for (Item i : itemRepository.findAll()) {
            m.put(i.getSku(), i.getName());
        }
        namesBySku = Map.copyOf(m);        // immutable once published
        return namesBySku;
    }
}
