-- inventory-service: may change only the quantity column of stock, and only
-- ever appends to the other three tables.
GRANT USAGE ON SCHEMA public TO inventory_svc;
GRANT SELECT, UPDATE (qty)  ON stock              TO inventory_svc;
GRANT SELECT, INSERT        ON low_stock_alerts   TO inventory_svc;
GRANT SELECT, INSERT        ON stock_commits      TO inventory_svc;
GRANT SELECT, INSERT        ON stock_commit_lines TO inventory_svc;
