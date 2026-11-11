package com.coachraffael.demos.manifest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestParserTest {

    static final String VALID = """
            slug: realestate-platform
            title: { en: "Real Estate Platform", de: "Immobilienplattform" }
            teaser: { en: "Listings, bookings, contracts" }
            category: showcase
            tags: [spring-boot, nextjs, mcp]
            visibility: public
            lifecycle: on-demand
            idle_minutes: 30
            services:
              web: { image: ghcr.io/coach-raffael/realestate-platform-web, port: 3000, expose: /, memory: 384m }
              api: { image: ghcr.io/coach-raffael/realestate-platform-api, port: 8080, expose: /api, memory: 512m, health: /api/actuator/health, depends_on: [db] }
              db:
                image: postgres:16-alpine
                memory: 256m
                volumes: [/var/lib/postgresql/data]
                env: { POSTGRES_DB: demo, POSTGRES_USER: demo }
            reset: { service: api, command: ["java", "-jar", "app.jar", "--reset-demo"], nightly: true }
            env_required: [DEMO_ADMIN_PASSWORD, POSTGRES_PASSWORD]
            quality:
              flows: [e2e/search-and-book.spec.ts]
              lighthouse: { performance: 85, accessibility: 95 }
            """;

    final ManifestParser parser = new ManifestParser();

    @Test
    void parsesValidManifest() {
        DemoManifest m = parser.parse(VALID);
        assertThat(m.slug()).isEqualTo("realestate-platform");
        assertThat(m.title()).containsEntry("de", "Immobilienplattform");
        assertThat(m.services()).containsOnlyKeys("web", "api", "db");
        assertThat(m.entryService()).isEqualTo("web");
        assertThat(m.smokePath()).isEqualTo("/api/actuator/health");
        assertThat(m.services().get("db").ownImage()).isFalse();
        assertThat(m.idleMinutes()).isEqualTo(30);
        assertThat(m.quality().lighthouse()).containsEntry("performance", 85);
        assertThat(m.reset().command()).containsExactly("java", "-jar", "app.jar", "--reset-demo");
    }

    @Test
    void appliesDefaults() {
        DemoManifest m = parser.parse("""
                slug: tiny
                title: Tiny
                services:
                  web: { image: ghcr.io/coach-raffael/tiny, port: 80, expose: /, memory: 64m }
                """);
        assertThat(m.title()).containsEntry("en", "Tiny");
        assertThat(m.visibility()).isEqualTo("private");
        assertThat(m.lifecycle()).isEqualTo("on-demand");
        assertThat(m.routing()).isEqualTo("path");
        assertThat(m.wake()).isEqualTo("anyone");
        assertThat(m.idleMinutes()).isEqualTo(60);
    }

    @Test
    void collectsAllProblemsAtOnce() {
        assertThatThrownBy(() -> parser.parse("""
                slug: API
                titel: oops
                lifecycle: ttl
                services:
                  web: { image: nginx:latest, expose: /x/, memory: 99g }
                """))
                .isInstanceOfSatisfying(ManifestException.class, e -> assertThat(e.problems()).anySatisfy(p -> assertThat(p).startsWith("slug"))
                        .anySatisfy(p -> assertThat(p).contains("titel: unbekanntes Feld"))
                        .anySatisfy(p -> assertThat(p).contains("title.en fehlt"))
                        .anySatisfy(p -> assertThat(p).contains("ttl_days"))
                        .anySatisfy(p -> assertThat(p).contains("services.web.image"))
                        .anySatisfy(p -> assertThat(p).contains("services.web.expose"))
                        .anySatisfy(p -> assertThat(p).contains("services.web.memory"))
                        .anySatisfy(p -> assertThat(p).contains("services.web.port")));
    }

    @Test
    void rejectsForeignImagesHostVolumesAndReservedSlugs() {
        String base = """
                slug: %s
                title: X
                services:
                  web: { image: %s, port: 80, expose: /, memory: 64m, volumes: [%s] }
                """;
        assertThatThrownBy(() -> parser.parse(base.formatted("api", "ghcr.io/coach-raffael/x", "/data")))
                .hasMessageContaining("vom Hub belegt");
        assertThatThrownBy(() -> parser.parse(base.formatted("x", "ghcr.io/someone-else/x", "/data")))
                .hasMessageContaining("services.web.image");
        assertThatThrownBy(() -> parser.parse(base.formatted("x", "ghcr.io/coach-raffael/x:latest", "/data")))
                .hasMessageContaining("ohne Tag");
        assertThatThrownBy(() -> parser.parse(base.formatted("x", "ghcr.io/coach-raffael/x", "../etc")))
                .hasMessageContaining("Host-Pfade");
        assertThatThrownBy(() -> parser.parse(base.formatted("x", "ghcr.io/coach-raffael/x", "/var/run/../../etc")))
                .hasMessageContaining("Host-Pfade");
    }

    @Test
    void needsExactlyOneEntryAndOwnImageAndMemoryBudget() {
        assertThatThrownBy(() -> parser.parse("""
                slug: x
                title: X
                services:
                  db: { image: postgres:16, memory: 256m }
                """)).hasMessageContaining("expose: /").hasMessageContaining("eigenes Image");
        assertThatThrownBy(() -> parser.parse("""
                slug: x
                title: X
                services:
                  a: { image: ghcr.io/coach-raffael/a, port: 1, expose: /, memory: 2g }
                  b: { image: ghcr.io/coach-raffael/b, port: 1, expose: /b, memory: 2g }
                """)).hasMessageContaining("zusammen höchstens");
    }

    @Test
    void hubVariablesAndSecretsCannotBeOverridden() {
        assertThatThrownBy(() -> parser.parse("""
                slug: x
                title: X
                env_required: [BASE_PATH, TOKEN]
                services:
                  web: { image: ghcr.io/coach-raffael/x, port: 1, expose: /, memory: 64m, env: { DEMO_SLUG: y, TOKEN: plain } }
                """)).hasMessageContaining("env_required: \"BASE_PATH\" setzt der Hub selbst")
                .hasMessageContaining("services.web.env.DEMO_SLUG")
                .hasMessageContaining("services.web.env.TOKEN: steht schon in env_required");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> parser.parse("- a\n- b")).hasMessageContaining("Objekt");
        assertThatThrownBy(() -> parser.parse("slug: [")).hasMessageContaining("kein gültiges YAML");
        assertThatThrownBy(() -> parser.parse(VALID.replace("e2e/search-and-book.spec.ts", "/abs/x.spec.ts")))
                .hasMessageContaining("quality.flows");
    }
}
