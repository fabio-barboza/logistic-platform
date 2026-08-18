-- Role read-only usada exclusivamente pela tool MCP execute_query.
-- A garantia contra escrita vive aqui, no banco, via GRANT/REVOKE — não em regex no código Java.

CREATE ROLE logistic_ro LOGIN PASSWORD 'logistic_ro';
GRANT CONNECT ON DATABASE logisticdb TO logistic_ro;
GRANT USAGE ON SCHEMA public TO logistic_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO logistic_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO logistic_ro;
