-- Test-only migration used to prove how Flyway reports a failing schema change.
-- It is deliberately broken: the second statement is invalid, so the whole migration must fail.
-- It lives outside classpath:db/migration so the application itself never picks it up.

CREATE TABLE migration_failure_probe (
    id bigint PRIMARY KEY
);

INSERT INTO this_table_does_not_exist (id) VALUES (1);
