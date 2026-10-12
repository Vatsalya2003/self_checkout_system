package com.selfcheckout.self_checkout.catalog;

import com.selfcheckout.self_checkout.catalog.result.CatalogItem;
import com.selfcheckout.self_checkout.persistence.repository.CatalogCache;
import org.springframework.stereotype.Service;
import java.util.List;

// CATALOG-SERVICE, business layer. The simplest service in the project: read the catalog,
// turn entities into DTOs. No transaction annotation needed because it's a
// single read and nothing is modified.
//
// Worth knowing: the ORDER of this list matters more than it looks. The load
// client treats catalog position as "popularity rank" for its Zipf sampler,
// so sorting by SKU makes SKU-000001 the hottest item in every test run.
@Service
public class CatalogService {

    private final CatalogCache catalog;

    // Constructor injection: Spring sees this needs the cache and passes it in
    public CatalogService(CatalogCache catalog) {
        this.catalog = catalog;
    }

    public List<CatalogItem> listItems() {
        // Served from memory. The catalog is seeded once at boot and never
        // changes during a run, so re-reading 2000 rows per request bought
        // nothing. Still maps to the domain record, so no persistence type
        // and no stock count leaves this layer.
        return catalog.all().stream()
                .map(e -> new CatalogItem(e.sku(), e.name(), e.price()))
                .toList();
    }
}
