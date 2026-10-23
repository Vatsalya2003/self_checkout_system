-- ============================================================================
-- roles.sql -- one PostgreSQL role per service. Run once per reset by
-- db/reset.sh, against the server (not any one service database).
--
-- Week 5: each role may CONNECT to its own database only (reset.sh revokes
-- CONNECT from PUBLIC and grants it to the one role). So a cross-service join
-- is not just forbidden by convention -- the connection itself is refused:
--   psql -U checkout_svc inventory_db  ->  permission denied for database
-- What each role may do INSIDE its database is in db/<service>/grants.sql.
--
-- Local Homebrew PostgreSQL uses trust authentication, so the roles have no
-- passwords: they separate privileges, not identities.
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
