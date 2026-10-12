package com.threadhub.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catches migrations that only work on a fresh database.
 *
 * <p>The test seeds a throwaway schema with {@code db/legacy/previous-release-schema.sql} - the
 * schema Hibernate produced for the previous release - together with rows in it, then lets the real
 * application configuration upgrade that database: Flyway (autoconfigured from
 * {@code application.properties}) baselines the pre-Flyway schema and applies every later migration,
 * and Hibernate validates the entity mappings against the result. The Spring context only starts if
 * both steps succeed.</p>
 *
 * <p>The upgraded schema is then compared, structure by structure, with the schema a fresh install
 * gets from the same migrations. A migration that assumes an empty database (for example adding a
 * {@code NOT NULL} column without a default, or recreating a table that already exists) fails here,
 * even though a fresh-install-only suite would stay green.</p>
 */
@SpringBootTest
@ContextConfiguration(initializers = LegacySchemaUpgradeTest.LegacyDatabaseInitializer.class)
@TestPropertySource(properties = {
        "jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
})
class LegacySchemaUpgradeTest {

    private static final String PREVIOUS_RELEASE_SCHEMA = "db/legacy/previous-release-schema.sql";
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final int LEGACY_USERS = 2;
    private static final int LEGACY_COMMUNITIES = 1;
    private static final int LEGACY_MEMBERSHIPS = 2;

    private static String legacySchema;
    private static String freshInstallSchema;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("A database seeded with the previous release's schema upgrades to the schema a fresh install gets")
    void previousReleaseSchemaIsUpgradedToTheCurrentSchema() {
        // Reaching this point means Flyway migrated the seeded database and Hibernate then validated
        // the entity mappings against it (ddl-auto=validate), because the EntityManagerFactory is
        // created after the Flyway initializer.
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");

        String baselineVersion = environment.getProperty("spring.flyway.baseline-version");
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("true");
        assertThat(baselineVersion).isEqualTo("1");

        // The pre-Flyway schema was adopted with a baseline instead of being recreated by V1.
        assertThat(historyEntries()).contains("BASELINE:" + baselineVersion);

        // The versions a fresh install of the same migrations produces, in version order.
        List<MigrationVersion> freshInstallVersions = createFreshInstall();
        assertThat(freshInstallVersions).isNotEmpty();

        // Every migration newer than the baseline was applied to the legacy database.
        List<MigrationVersion> expectedUpgrades = freshInstallVersions.stream()
                .filter(version -> version.isNewerThan(MigrationVersion.fromVersion(baselineVersion)))
                .toList();
        assertThat(sqlVersionsFromHistory()).containsExactlyElementsOf(expectedUpgrades);

        // ... and the upgraded database is now at the newest version of all.
        List<MigrationVersion> appliedVersions = new ArrayList<>(sqlVersionsFromHistory());
        appliedVersions.add(MigrationVersion.fromVersion(baselineVersion));
        MigrationVersion currentVersion = appliedVersions.stream()
                .max(Comparator.naturalOrder())
                .orElseThrow();
        assertThat(currentVersion).isEqualTo(freshInstallVersions.getLast());

        // Existing rows survive the upgrade and the upgraded identity columns keep working.
        assertThat(count("users")).isEqualTo(LEGACY_USERS);
        assertThat(count("communities")).isEqualTo(LEGACY_COMMUNITIES);
        assertThat(count("community_members")).isEqualTo(LEGACY_MEMBERSHIPS);
        assertThat(jdbcTemplate.queryForObject(
                "select username from users where username = 'legacy_owner'", String.class))
                .isEqualTo("legacy_owner");
        jdbcTemplate.update("""
                insert into users (created_at, updated_at, email, password, username)
                values (now(), now(), 'after-upgrade@example.com', 'hash', 'after_upgrade')
                """);
        assertThat(count("users")).isEqualTo(LEGACY_USERS + 1);

        // The upgraded schema is structurally identical to a fresh install from the same migrations.
        assertThat(schemaSnapshot(legacySchema))
                .as("the upgraded previous-release schema must equal the schema of a fresh install")
                .isEqualTo(schemaSnapshot(freshInstallSchema));
    }

    /**
     * Creates a database that looks like the previous release: the legacy tables plus rows in them,
     * without any Flyway bookkeeping. The application is then pointed at it with the documented
     * baselining behaviour enabled, so Flyway never runs V1 against tables that already exist.
     */
    static class LegacyDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            legacySchema = "legacy_upgrade_" + System.nanoTime();
            String url = environmentValue("DB_URL");

            try (Connection connection = DriverManager.getConnection(url,
                    environmentValue("DB_USERNAME"), environmentValue("DB_PASSWORD"));
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + legacySchema);
                statement.execute("SET search_path TO " + legacySchema);
                for (String sql : statementsOf(PREVIOUS_RELEASE_SCHEMA)) {
                    statement.execute(sql);
                }
                for (String sql : legacyRows()) {
                    statement.execute(sql);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not seed the previous-release database", e);
            }

            TestPropertyValues.of(
                    "spring.datasource.url=" + url + (url.contains("?") ? "&" : "?") + "currentSchema=" + legacySchema,
                    "spring.flyway.baseline-on-migrate=true",
                    "spring.flyway.baseline-version=1"
            ).applyTo(applicationContext);
        }

        private static List<String> legacyRows() {
            return List.of(
                    """
                    insert into users (created_at, updated_at, email, password, username)
                    values (now(), now(), 'owner@legacy.example.com', 'legacy-hash', 'legacy_owner')
                    """,
                    """
                    insert into users (created_at, updated_at, email, password, username)
                    values (now(), now(), 'member@legacy.example.com', 'legacy-hash', 'legacy_member')
                    """,
                    """
                    insert into communities (created_at, updated_at, name, description, owner_id)
                    select now(), now(), 'legacy_community', 'Created before Flyway', id
                    from users where username = 'legacy_owner'
                    """,
                    """
                    insert into community_members (joined_at, role, community_id, user_id)
                    select now(), 'OWNER', c.id, u.id
                    from communities c join users u on u.username = 'legacy_owner'
                    where c.name = 'legacy_community'
                    """,
                    """
                    insert into community_members (joined_at, role, community_id, user_id)
                    select now(), 'MEMBER', c.id, u.id
                    from communities c join users u on u.username = 'legacy_member'
                    where c.name = 'legacy_community'
                    """);
        }
    }

    @AfterAll
    static void dropTemporarySchemas() throws SQLException {
        try (Connection connection = DriverManager.getConnection(environmentValue("DB_URL"),
                environmentValue("DB_USERNAME"), environmentValue("DB_PASSWORD"));
             Statement statement = connection.createStatement()) {
            if (legacySchema != null) {
                statement.execute("DROP SCHEMA IF EXISTS " + legacySchema + " CASCADE");
            }
            if (freshInstallSchema != null) {
                statement.execute("DROP SCHEMA IF EXISTS " + freshInstallSchema + " CASCADE");
            }
        }
    }

    /** Builds the schema a fresh install of the migrations produces, in its own throwaway schema. */
    private List<MigrationVersion> createFreshInstall() {
        freshInstallSchema = "fresh_install_" + System.nanoTime();
        Flyway freshInstall = Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .schemas(freshInstallSchema)
                .createSchemas(true)
                .load();
        freshInstall.migrate();
        return appliedSqlVersions(freshInstall);
    }

    private List<String> historyEntries() {
        return jdbcTemplate.queryForList(
                "select type || ':' || version from flyway_schema_history order by installed_rank", String.class);
    }

    private List<MigrationVersion> sqlVersionsFromHistory() {
        return jdbcTemplate.queryForList(
                        "select version from flyway_schema_history where type = 'SQL' order by installed_rank", String.class)
                .stream()
                .map(MigrationVersion::fromVersion)
                .toList();
    }

    private static List<MigrationVersion> appliedSqlVersions(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(version -> version != null)
                .sorted()
                .toList();
    }

    private Integer count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    /**
     * Captures the structure of a schema: columns with their type, length, nullability and identity,
     * primary keys, unique constraints, foreign keys and check constraints. Constraint names are
     * deliberately not compared, because the legacy schema carries Hibernate's generated
     * {@code uk...}/{@code fk...} names while the migrations use readable ones.
     */
    private List<String> schemaSnapshot(String schema) {
        List<String> snapshot = new ArrayList<>();

        snapshot.addAll(jdbcTemplate.queryForList("""
                select table_name || '.' || column_name || ' ' || data_type
                       || '(' || coalesce(character_maximum_length::text, '-') || ')'
                       || ' nullable=' || is_nullable || ' identity=' || is_identity
                from information_schema.columns
                where table_schema = ? and table_name not like 'flyway%'
                """, String.class, schema));

        snapshot.addAll(jdbcTemplate.queryForList("""
                select tc.table_name || ' ' || tc.constraint_type || '('
                       || string_agg(kcu.column_name, ',' order by kcu.ordinal_position) || ')'
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on kcu.constraint_name = tc.constraint_name and kcu.table_schema = tc.table_schema
                where tc.table_schema = ? and tc.table_name not like 'flyway%'
                  and tc.constraint_type in ('PRIMARY KEY', 'UNIQUE')
                group by tc.table_name, tc.constraint_name, tc.constraint_type
                """, String.class, schema));

        snapshot.addAll(jdbcTemplate.queryForList("""
                select tc.table_name || '.' || kcu.column_name || ' -> ' || ccu.table_name || '.' || ccu.column_name
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on kcu.constraint_name = tc.constraint_name and kcu.table_schema = tc.table_schema
                join information_schema.constraint_column_usage ccu
                  on ccu.constraint_name = tc.constraint_name and ccu.table_schema = tc.table_schema
                where tc.table_schema = ? and tc.constraint_type = 'FOREIGN KEY'
                """, String.class, schema));

        for (Map<String, Object> check : jdbcTemplate.queryForList("""
                select rel.relname as table_name,
                       (select string_agg(att.attname, ',' order by att.attname)
                          from pg_attribute att
                         where att.attrelid = con.conrelid and att.attnum = any (con.conkey)) as columns,
                       pg_get_constraintdef(con.oid) as definition
                from pg_constraint con
                join pg_class rel on rel.oid = con.conrelid
                join pg_namespace n on n.oid = rel.relnamespace
                where con.contype = 'c' and n.nspname = ?
                """, schema)) {
            // Hibernate writes the enum check as `role = ANY (ARRAY['OWNER', ...])` while V1 uses
            // `role IN ('OWNER', ...)`, so compare the column and the allowed values, not the text.
            List<String> allowedValues = new ArrayList<>();
            Matcher matcher = Pattern.compile("'([^']*)'").matcher((String) check.get("definition"));
            while (matcher.find()) {
                allowedValues.add(matcher.group(1));
            }
            Collections.sort(allowedValues);
            snapshot.add(check.get("table_name") + " CHECK " + check.get("columns")
                    + " in (" + String.join(",", allowedValues) + ")");
        }

        snapshot.sort(String::compareTo);
        return snapshot;
    }

    private static List<String> statementsOf(String classpathResource) {
        try (InputStream stream = LegacySchemaUpgradeTest.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing test resource: " + classpathResource);
            }
            return Arrays.stream(new String(stream.readAllBytes(), StandardCharsets.UTF_8).split(";"))
                    .map(String::trim)
                    .filter(statement -> !statement.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read test resource: " + classpathResource, e);
        }
    }

    private static String environmentValue(String name) {
        return Optional.ofNullable(System.getenv(name))
                .or(() -> Optional.ofNullable(System.getProperty(name)))
                .orElseThrow(() -> new IllegalStateException(
                        name + " is not set; the migration tests need the same database environment as the application"));
    }
}
