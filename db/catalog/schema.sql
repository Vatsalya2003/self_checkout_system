-- ============================================================================
-- catalog_db -- owned by catalog-service. Week 5: every service has its own
-- database, and only its own role can connect to it (db/roles.sql).
--
-- The catalog is the one thing in here: what the store sells and for how much.
-- Stock is NOT here any more -- it moved to inventory_db, because each service
-- owns its own data and stock is inventory's business.
-- ============================================================================
DROP TABLE IF EXISTS items;

CREATE TABLE items (
    sku   VARCHAR(32) PRIMARY KEY,          -- the SKU string is the key
    name  VARCHAR(255) NOT NULL,
    price NUMERIC(10, 2) NOT NULL CHECK (price >= 0)   -- NUMERIC: exact money
);
