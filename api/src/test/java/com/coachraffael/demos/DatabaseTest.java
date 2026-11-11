package com.coachraffael.demos;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Basis für Tests gegen echtes Postgres (Trigger und jsonb lassen sich nicht sinnvoll mocken) – wie im CRM.
 * <ul>
 *   <li>{@code TEST_DB_URL} gesetzt: diese Datenbank (Benutzer/Passwort {@code TEST_DB_USER}/{@code TEST_DB_PASSWORD}).</li>
 *   <li>sonst Testcontainers – in CI ({@code CI=true}).</li>
 * </ul>
 * Aktiviert mit {@code @EnabledIf("com.coachraffael.demos.DatabaseTest#available")}. Tests legen eigene Daten mit
 * eindeutigen Slugs an und prüfen relativ.
 */
public abstract class DatabaseTest {

    private static PostgreSQLContainer<?> container;

    public static boolean available() {
        return System.getenv("TEST_DB_URL") != null || "true".equals(System.getenv("CI"));
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DB_URL");
        if (url != null) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> env("TEST_DB_USER", "demos"));
            registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "demos"));
        } else {
            synchronized (DatabaseTest.class) {
                if (container == null) {
                    container = new PostgreSQLContainer<>("postgres:16-alpine");
                    container.start();
                }
            }
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("spring.datasource.hikari.idle-timeout", () -> "10000");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://localhost:1/realms/test");
        registry.add("hub.ci-token", () -> "hubci_test-token-0123456789");
        registry.add("hub.secret-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("hub.public-url", () -> "https://demos.example.test");
        registry.add("hub.demo-domain", () -> "demos.example.test");
        registry.add("hub.data-dir", () -> System.getProperty("java.io.tmpdir") + "/showcase-hub-test");
        // Hintergrundjob in Tests nicht laufen lassen
        registry.add("hub.reaper-initial-delay-ms", () -> "3600000");
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : v;
    }
}
