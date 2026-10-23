-- 2,000 items, SKU-000001 ... SKU-002000. Deterministic, so every run sees the
-- same catalog. inventory/data.sql seeds stock for exactly the same SKUs with
-- the same generate_series -- that is deployment data, not a runtime read of
-- this database.
INSERT INTO items (sku, name, price)
SELECT 'SKU-' || lpad(n::text, 6, '0'),         -- lpad so they sort correctly as text
       'Item ' || n,
       round((1 + (n % 50) * 0.37)::numeric, 2)  -- prices between $1.00 and ~$19
FROM generate_series(1, 2000) AS n;
