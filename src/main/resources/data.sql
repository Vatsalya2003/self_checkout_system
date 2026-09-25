-- ============================================================================
-- data.sql -- seed data, re-run on every app start right after schema.sql.
-- The assignment fixes these numbers: 2000 items, 10,000 units of each at the
-- start of every test run.
-- ============================================================================

-- generate_series(1, 2000) is a Postgres trick that produces the numbers
-- 1..2000 as rows, so one INSERT creates the whole catalog -- no loop needed.
INSERT INTO items (sku, name, price, stock)
SELECT
    'SKU-' || lpad(n::text, 6, '0'),        -- SKU-000001 ... SKU-002000
                                            -- lpad zero-pads so they sort correctly as text
    'Item ' || n,
    round((1 + (n % 50) * 0.37)::numeric, 2), -- deterministic prices between $1.00 and ~$19
                                              -- deterministic = same catalog every run,
                                              -- so results are comparable across runs
    10000
FROM generate_series(1, 2000) AS n;
