-- analytics-service: stores (and numbers) the scans checkout publishes, and its windows.
GRANT USAGE ON SCHEMA public TO analytics_svc;
GRANT SELECT, INSERT ON scan_log          TO analytics_svc;
GRANT SELECT, INSERT ON scan_batches      TO analytics_svc;
GRANT SELECT, INSERT ON popular_snapshots TO analytics_svc;
GRANT USAGE ON SEQUENCE popular_snapshots_id_seq TO analytics_svc;
