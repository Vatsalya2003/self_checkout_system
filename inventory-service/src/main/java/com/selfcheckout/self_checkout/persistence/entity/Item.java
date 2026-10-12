package com.selfcheckout.self_checkout.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

// INVENTORY-SERVICE, persistence layer.
// One row of the "items" table, as inventory-service sees it: READ-ONLY.
// Stock is changed by checkout-service alone, under row locks, with plain SQL;
// this copy of the entity deliberately has no setter, so this service cannot
// change stock even by accident. Mapped only so the low-stock JPQL query can
// join items against low_stock_alerts.

@Entity                 // "this class maps to a DB table"
@Table(name = "items")  // "...specifically, this one"
public class Item {

    @Id                 // primary key column. The SKU string IS the key --
    private String sku; // no separate auto-increment id needed.

    private String name;
    private BigDecimal price;   // BigDecimal, never double, for money
    private int stock;

    protected Item() {}  // JPA needs an empty constructor to build objects from rows

    public String getSku() { return sku; }
    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public int getStock() { return stock; }

}
