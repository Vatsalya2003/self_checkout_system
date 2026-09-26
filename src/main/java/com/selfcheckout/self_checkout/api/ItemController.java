package com.selfcheckout.self_checkout.api;

import com.selfcheckout.self_checkout.api.dto.CatalogResponse;
import com.selfcheckout.self_checkout.api.dto.ItemDto;
import com.selfcheckout.self_checkout.transactions.CatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// API layer. Maps HTTP to the transactions layer and nothing else -- no
// business logic here on purpose.
// @RestController = @Controller + @ResponseBody, i.e. whatever we return gets
// serialized straight to JSON instead of being treated as a view name.
@RestController
public class ItemController {

    private final CatalogService catalogService;

    public ItemController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // The load client calls this once at startup to learn the catalog.
    // Honest note: this method is almost a pass-through. Mapping CatalogItem
    // to ItemDto looks like busywork today, and it is -- the value only shows
    // up the day the wire format and the business model stop agreeing.
    @GetMapping("/items")
    public CatalogResponse listItems() {
        return new CatalogResponse(catalogService.listItems().stream()
                .map(i -> new ItemDto(i.sku(), i.name(), i.price()))
                .toList());
    }
}
