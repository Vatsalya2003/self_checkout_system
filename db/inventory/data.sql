-- 10,000 units of every SKU the catalog sells (same generate_series as
-- catalog/data.sql).
INSERT INTO stock (sku, qty)
SELECT 'SKU-' || lpad(n::text, 6, '0'), 10000
FROM generate_series(1, 2000) AS n;
