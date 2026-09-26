package com.selfcheckout.self_checkout.transactions;

import com.selfcheckout.self_checkout.transactions.result.CatalogItem;
import com.selfcheckout.self_checkout.persistence.repository.ItemRepository;
import org.springframework.stereotype.Service;
import java.util.List;

// TRANSACTIONS layer. The simplest service in the project: read the catalog,
// turn entities into DTOs. No transaction annotation needed because it's a
// single read and nothing is modified.
//
// Worth knowing: the ORDER of this list matters more than it looks. The load
// client treats catalog position as "popularity rank" for its Zipf sampler,
// so sorting by SKU makes SKU-000001 the hottest item in every test run.
@Service
public class CatalogService {

    private final ItemRepository itemRepository;

    // Constructor injection: Spring sees this needs an ItemRepository and passes one in
    public CatalogService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public List<CatalogItem> listItems() {
        return itemRepository.findAllByOrderBySkuAsc().stream()
                // map entity -> domain result so no persistence type, and no
                // stock count, ever leaves this layer
                .map(i -> new CatalogItem(i.getSku(), i.getName(), i.getPrice()))
                .toList();
    }
}
