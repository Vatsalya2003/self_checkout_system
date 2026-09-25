package com.selfcheckout.self_checkout.controller;

import com.selfcheckout.self_checkout.dto.LowStockResponse;
import com.selfcheckout.self_checkout.service.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// CONTROLLER layer. Inventory questions (currently just low stock).
@RestController
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    // required = false + Integer (not int) so the parameter can be absent.
    // An int would default to 0 and we could not tell "not supplied" apart
    // from "supplied as zero"; null makes that distinction explicit.
    @GetMapping("/inventory/low-stock")
    public LowStockResponse lowStock(@RequestParam(required = false) Integer threshold) {
        return inventoryService.lowStock(threshold);
    }
}
