package com.threadhub.migration;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the ThreadHub database schema is owned by Flyway: a fresh schema is created from
 * the versioned migrations alone, Hibernate only validates it, an already migrated schema is left
 * untouched, and a failing migration fails loudly instead of leaving a partial schema.
 *
 * <p>These tests run against the PostgreSQL service configured through {@code DB_URL} - the same
 * database the rest of the suite uses - and create their own throwaway schemas for the isolated
 * scenarios.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
})
class FlywayMigrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final String BROKEN_MIGRATION_LOCATION = "classpath:db/broken-migration";

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final List<String> probeSchemas = new ArrayList<>();

    @AfterEach
    void dropProbeSchemas() {
        for (String schema : probeSchemas) {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
        probeSchemas.clear();
    }

    @Test
    @DisplayName("The application schema is created by V1 and Hibernate validates it instead of updating it")
    void applicationSchemaIsOwnedByFlywayAndValidatedByHibernate() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.locations")).isEqualTo(MIGRATION_LOCATION);

        // The context only starts if Hibernate's schema validation passed, because the
        // EntityManagerFactory is built after Flyway has migrated the schema.
        assertThat(entityManagerFactory).isNotNull();

        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(current.getVersion().getVersion()).isEqualTo("1");
        assertThat(current.getDescription()).isEqualTo("init schema");
        assertThat(current.getState().getDisplayName()).isEqualTo("Success");

        assertThat(flyway.info().applied()).hasSize(1);
        assertThat(expectedApplicationTables()).allSatisfy(table ->
                assertThat(publicTableExists(table)).as("table %s exists", table).isTrue());
    }

    @Test
    @DisplayName("V1 creates the expected columns, primary keys, unique constraints, foreign keys and enum check")
    void migrationCreatesExpectedSchema() {
        assertThat(columnType("users", "id")).isEqualTo("bigint");
        assertThat(isIdentity("users", "id")).isTrue();
        assertThat(columnType("communities", "description")).isEqualTo("character varying");
        assertThat(columnLength("communities", "description")).isEqualTo(1000);
        assertThat(isNullable("communities", "description")).isTrue();
        assertThat(columnType("communities", "owner_id")).isEqualTo("bigint");
        assertThat(isNullable("communities", "owner_id")).isFalse();
        assertThat(columnType("community_members", "role")).isEqualTo("character varying");
        assertThat(isNullable("community_members", "role")).isFalse();
        assertThat(columnType("community_members", "joined_at")).isEqualTo("timestamp without time zone");
        assertThat(isNullable("community_members", "joined_at")).isFalse();
        assertThat(isNullable("users", "updated_at")).isFalse();

        assertThat(primaryKeyColumns("users")).containsExactly("id");
        assertThat(primaryKeyColumns("communities")).containsExactly("id");
        assertThat(primaryKeyColumns("community_members")).containsExactly("id");

        assertThat(uniqueConstraintColumns("users", "username")).containsExactly("username");
        assertThat(uniqueConstraintColumns("users", "email")).containsExactly("email");
        assertThat(uniqueConstraintColumns("communities", "name")).containsExactly("name");
        assertThat(uniqueConstraintColumns("community_members", "uk_user_community"))
                .containsExactly("user_id", "community_id");

        assertThat(foreignKey("communities", "owner_id")).isEqualTo("users(id)");
        assertThat(foreignKey("community_members", "community_id")).isEqualTo("communities(id)");
        assertThat(foreignKey("community_members", "user_id")).isEqualTo("users(id)");

        assertThat(checkConstraintDefinition("community_members"))
                .contains("OWNER")
                .contains("MODERATOR")
                .contains("MEMBER")
                .contains("role");
    }

    @Test
    @DisplayName("The role CHECK constraint accepts OWNER, MODERATOR and MEMBER and rejects anything else")
    void roleCheckConstraintAcceptsSupportedRolesAndRejectsOthers() {
        assertThat(checkConstraintDefinition("community_members"))
                .as("the CHECK constraint must allow every CommunityMemberRole value")
                .contains("OWNER")
                .contains("MODERATOR")
                .contains("MEMBER");

        String schema = newProbeSchema("flyway_roles");
        flywayFor(schema, MIGRATION_LOCATION).migrate();

        long communityId = seedCommunityOwnedBy(schema, "owner");
        for (String role : List.of("OWNER", "MODERATOR", "MEMBER")) {
            insertUser(schema, "member_" + role.toLowerCase());
            insertMembership(schema, communityId, "member_" + role.toLowerCase(), role);
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from " + schema + ".community_members", Integer.class))
                .as("all three supported roles must be storable")
                .isEqualTo(3);

        insertUser(schema, "intruder");
        assertThatThrownBy(() -> insertMembership(schema, communityId, "intruder", "ADMIN"))
                .as("an unknown role must still be rejected by the database")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("community_members_role_check");
    }

    private void insertUser(String schema, String username) {
        jdbcTemplate.update("""
                insert into %s.users (created_at, updated_at, email, password, username)
                values (now(), now(), ?, 'hash', ?)
                """.formatted(schema), username + "@example.com", username);
    }

    private long seedCommunityOwnedBy(String schema, String ownerUsername) {
        insertUser(schema, ownerUsername);
        Long id = jdbcTemplate.queryForObject("""
                insert into %s.communities (created_at, updated_at, name, description, owner_id)
                select now(), now(), ?, null, id from %s.users where username = ?
                returning id
                """.formatted(schema, schema), Long.class, "roles_" + schema, ownerUsername);
        return id;
    }

    private void insertMembership(String schema, long communityId, String username, String role) {
        jdbcTemplate.update("""
                insert into %s.community_members (joined_at, role, community_id, user_id)
                select now(), ?, ?, u.id from %s.users u where u.username = ?
                """.formatted(schema, schema), role, communityId, username);
    }

    @Test
    @DisplayName("A fresh schema is initialised by Flyway exactly once and re-running applies nothing")
    void freshSchemaIsMigratedOnceAndMigrationIsIdempotent() {
        String schema = newProbeSchema("flyway_fresh");

        MigrateResult firstRun = flywayFor(schema, MIGRATION_LOCATION).migrate();

        assertThat(firstRun.migrationsExecuted).isEqualTo(1);
        assertThat(firstRun.success).isTrue();
        for (String table : expectedApplicationTables()) {
            assertThat(schemaTableExists(schema, table)).as("table %s exists in %s", table, schema).isTrue();
        }
        assertThat(appliedSqlMigrations(schema)).isEqualTo(1);
        assertThat(successfulHistoryRowsForVersion(schema, "1")).isEqualTo(1);

        MigrateResult secondRun = flywayFor(schema, MIGRATION_LOCATION).migrate();

        assertThat(secondRun.migrationsExecuted).isZero();
        assertThat(secondRun.success).isTrue();
        assertThat(appliedSqlMigrations(schema)).isEqualTo(1);
    }

    @Test
    @DisplayName("A failing migration fails loudly and leaves no partially created schema")
    void failingMigrationSurfacesClearlyAndRollsBack() {
        String schema = newProbeSchema("flyway_broken");

        // V1 is the real initial migration; V2 is a deliberately broken test-only migration.
        assertThatThrownBy(() -> flywayFor(schema, MIGRATION_LOCATION, BROKEN_MIGRATION_LOCATION).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V2__broken_migration");

        // V1 succeeded, V2 failed and was rolled back together with the table it created.
        assertThat(schemaTableExists(schema, "users")).isTrue();
        assertThat(schemaTableExists(schema, "migration_failure_probe")).isFalse();
        assertThat(successfulHistoryRowsForVersion(schema, "2")).isZero();

        // A later attempt keeps failing instead of silently continuing with a partial schema.
        assertThatThrownBy(() -> flywayFor(schema, MIGRATION_LOCATION, BROKEN_MIGRATION_LOCATION).migrate())
                .isInstanceOf(FlywayException.class);
    }

    private Flyway flywayFor(String schema, String... locations) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .schemas(schema)
                .createSchemas(true)
                .load();
    }

    private String newProbeSchema(String prefix) {
        String schema = prefix + "_" + System.nanoTime();
        probeSchemas.add(schema);
        return schema;
    }

    private List<String> expectedApplicationTables() {
        return List.of("users", "communities", "community_members");
    }

    private boolean publicTableExists(String table) {
        return schemaTableExists("public", table);
    }

    private boolean schemaTableExists(String schema, String table) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = ? and table_name = ? and table_type = 'BASE TABLE'
                """, Integer.class, schema, table);
        return count != null && count > 0;
    }

    private String columnType(String table, String column) {
        return jdbcTemplate.queryForObject("""
                select data_type from information_schema.columns
                where table_schema = 'public' and table_name = ? and column_name = ?
                """, String.class, table, column);
    }

    private Integer columnLength(String table, String column) {
        return jdbcTemplate.queryForObject("""
                select character_maximum_length from information_schema.columns
                where table_schema = 'public' and table_name = ? and column_name = ?
                """, Integer.class, table, column);
    }

    private boolean isNullable(String table, String column) {
        String nullable = jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                where table_schema = 'public' and table_name = ? and column_name = ?
                """, String.class, table, column);
        return "YES".equals(nullable);
    }

    private boolean isIdentity(String table, String column) {
        String identity = jdbcTemplate.queryForObject("""
                select is_identity from information_schema.columns
                where table_schema = 'public' and table_name = ? and column_name = ?
                """, String.class, table, column);
        return "YES".equals(identity);
    }

    private List<String> primaryKeyColumns(String table) {
        return jdbcTemplate.queryForList("""
                select kcu.column_name
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on kcu.constraint_name = tc.constraint_name and kcu.table_schema = tc.table_schema
                where tc.table_schema = 'public' and tc.table_name = ? and tc.constraint_type = 'PRIMARY KEY'
                order by kcu.ordinal_position
                """, String.class, table);
    }

    private List<String> uniqueConstraintColumns(String table, String constraintLike) {
        return jdbcTemplate.queryForList("""
                select kcu.column_name
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on kcu.constraint_name = tc.constraint_name and kcu.table_schema = tc.table_schema
                where tc.table_schema = 'public' and tc.table_name = ? and tc.constraint_type = 'UNIQUE'
                  and tc.constraint_name like ?
                order by kcu.ordinal_position
                """, String.class, table, "%" + constraintLike + "%");
    }

    private String foreignKey(String table, String column) {
        List<String> targets = jdbcTemplate.queryForList("""
                select ccu.table_name || '(' || ccu.column_name || ')'
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on kcu.constraint_name = tc.constraint_name and kcu.table_schema = tc.table_schema
                join information_schema.constraint_column_usage ccu
                  on ccu.constraint_name = tc.constraint_name and ccu.table_schema = tc.table_schema
                where tc.table_schema = 'public' and tc.table_name = ? and tc.constraint_type = 'FOREIGN KEY'
                  and kcu.column_name = ?
                """, String.class, table, column);
        return targets.isEmpty() ? null : targets.getFirst();
    }

    private String checkConstraintDefinition(String table) {
        return jdbcTemplate.queryForObject("""
                select pg_get_constraintdef(con.oid)
                from pg_constraint con
                join pg_class rel on rel.oid = con.conrelid
                join pg_namespace n on n.oid = rel.relnamespace
                where con.contype = 'c' and n.nspname = 'public' and rel.relname = ?
                """, String.class, table);
    }

    /**
     * Counts applied SQL migrations only. Creating the schema itself also inserts a
     * {@code SCHEMA} row into the history table, which is not a versioned migration.
     */
    private Integer appliedSqlMigrations(String schema) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + schema + ".flyway_schema_history where type = 'SQL'", Integer.class);
    }

    private Integer successfulHistoryRowsForVersion(String schema, String version) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + schema + ".flyway_schema_history where version = ? and success = true",
                Integer.class, version);
    }
}
