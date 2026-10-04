package com.selfcheckout.self_checkout.persistence.repository;

// PERSISTENCE layer (database access).
// "What is this SKU called?" -- used when ranking a window for display.
public interface CatalogNameLookup {
    String nameOf(String sku);
}
