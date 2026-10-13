-- ============================================================================
-- roles.sql -- one PostgreSQL role per service, granted only what it uses.
--
-- Run by db/reset.sh after schema.sql and data.sql. The tables are recreated
-- on every reset and grants belong to the table, so the grants are re-applied
-- each time; the roles themselves are created once and then left alone.
--
-- This is the database-side copy of the ownership matrix (ARCHITECTURE.md
-- § Week 4, W4.3). The ArchUnit tests check the same rules in the code; these
-- grants make the database itself refuse a write from the wrong service.
--
-- Local Homebrew PostgreSQL uses trust authentication, so these roles have no
-- passwords: they separate PRIVILEGES, not identities. A real deployment would
-- give each role a password or certificate.
-- ============================================================================

DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['catalog_svc', 'checkout_svc', 'inventory_svc', 'analytics_svc'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I LOGIN', r);
        END IF;
    END LOOP;
END $$;

-- Nobody gets anything by default: a table the grants below do not mention is
-- closed to every service.
REVOKE ALL ON ALL TABLES    IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO catalog_svc, checkout_svc, inventory_svc, analytics_svc;

-- catalog-service: reads the catalog. Writes nothing.
GRANT SELECT ON items TO catalog_svc;

-- checkout-service: owns the sale. UPDATE on items is limited to the stock
-- column -- it may not rename or reprice anything. (The row lock
-- "SELECT ... FOR NO KEY UPDATE" needs UPDATE on at least one column, which
-- this is.) No UPDATE or DELETE anywhere else: every other table it writes is
-- append-only.
GRANT SELECT, UPDATE (stock)  ON items             TO checkout_svc;
GRANT SELECT, INSERT          ON transactions      TO checkout_svc;
GRANT SELECT, INSERT          ON transaction_items TO checkout_svc;
GRANT SELECT, INSERT          ON low_stock_alerts  TO checkout_svc;
GRANT SELECT, INSERT          ON scan_log          TO checkout_svc;

-- inventory-service: reads the alerts and the catalog they join to.
GRANT SELECT ON low_stock_alerts, items TO inventory_svc;

-- analytics-service: reads the scan hand-off and the catalog (for names),
-- writes only its own windows.
GRANT SELECT          ON scan_log, items          TO analytics_svc;
GRANT SELECT, INSERT  ON popular_snapshots        TO analytics_svc;
GRANT USAGE           ON SEQUENCE popular_snapshots_id_seq TO analytics_svc;
