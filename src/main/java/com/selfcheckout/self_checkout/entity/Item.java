package com.selfcheckout.self_checkout.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

// ENTITY layer. One row of the "items" table = one product in the catalog.
// This is the ONLY place stock lives, which is why every stock change has to
// go through a locked read of this row (see ItemRepository.findBySkuForUpdate).

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

    // The only mutator. Called during complete() while we hold this row's lock.
    // Because the object is "managed" by JPA at that point, we don't write any
    // UPDATE ourselves -- Hibernate notices the field changed (dirty checking)
    // and writes the UPDATE when the transaction commits.
    public void setStock(int stock) { this.stock = stock; }
}
