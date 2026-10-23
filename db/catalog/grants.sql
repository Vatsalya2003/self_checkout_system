-- catalog-service only reads the catalog.
GRANT USAGE ON SCHEMA public TO catalog_svc;
GRANT SELECT ON items TO catalog_svc;
