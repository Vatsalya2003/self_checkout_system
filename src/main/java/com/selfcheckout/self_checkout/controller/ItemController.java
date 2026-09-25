package com.selfcheckout.self_checkout.controller;

import com.selfcheckout.self_checkout.dto.CatalogResponse;
import com.selfcheckout.self_checkout.service.CatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// CONTROLLER layer. Maps HTTP to the service and nothing else -- no business
// logic here on purpose.
// @RestController = @Controller + @ResponseBody, i.e. whatever we return gets
// serialized straight to JSON instead of being treated as a view name.
@RestController
public class ItemController {

    private final CatalogService catalogService;

    public ItemController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // The load client calls this once at startup to learn the catalog.
    @GetMapping("/items")
    public CatalogResponse listItems() {
        return new CatalogResponse(catalogService.listItems());
    }
}
