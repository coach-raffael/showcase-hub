package com.coachraffael.demos.compose;

import com.coachraffael.demos.common.HubProperties;
import com.coachraffael.demos.manifest.DemoManifest;
import com.coachraffael.demos.manifest.ManifestParser;
import com.coachraffael.demos.manifest.ManifestParserTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposeRendererTest {

    static final String WEB = "ghcr.io/coach-raffael/realestate-platform-web@sha256:" + "a".repeat(64);
    static final String API = "ghcr.io/coach-raffael/realestate-platform-api@sha256:" + "b".repeat(64);

    final HubProperties props = new HubProperties("https://demos.coach-raffael.com", "demos.coach-raffael.com", null,
            null, null, null, new HubProperties.Github(null, "coach-raffael/showcase-hub", null));
    final ComposeRenderer renderer = new ComposeRenderer(props);
    final DemoManifest manifest = new ManifestParser().parse(ManifestParserTest.VALID);
    final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    JsonNode render(DemoManifest m, String visibility) throws Exception {
        String out = renderer.render(m, Map.of("web", WEB, "api", API), visibility);
        assertThat(out).startsWith("# Erzeugt vom Showcase-Hub");
        return yaml.readTree(out);
    }

    static List<String> list(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return out;
    }

    @Test
    void pathRoutingUnderTheSlug() throws Exception {
        JsonNode doc = render(manifest, "public");
        assertThat(doc.path("name").asText()).isEqualTo("demo-realestate-platform");
        JsonNode web = doc.path("services").path("web");
        assertThat(web.path("image").asText()).isEqualTo(WEB);
        assertThat(web.path("mem_limit").asText()).isEqualTo("384m");
        assertThat(list(web.path("environment"))).contains("BASE_PATH=/realestate-platform", "DEMO_SLUG=realestate-platform",
                "DEMO_PUBLIC_URL=https://demos.coach-raffael.com/realestate-platform", "DEMO_COOKIE_PREFIX=realestate-platform",
                "DEMO_ADMIN_PASSWORD=${DEMO_ADMIN_PASSWORD}");
        List<String> webLabels = list(web.path("labels"));
        assertThat(webLabels).contains(
                "coach-raffael.demo=realestate-platform",
                "traefik.enable=true",
                "traefik.http.routers.demo-realestate-platform-web.rule=Host(`demos.coach-raffael.com`) && (Path(`/realestate-platform`) || PathPrefix(`/realestate-platform/`))",
                "traefik.http.routers.demo-realestate-platform-web.entrypoints=websecure",
                "traefik.http.routers.demo-realestate-platform-web.tls.certresolver=letsencrypt",
                "traefik.http.services.demo-realestate-platform-web.loadbalancer.server.port=3000",
                "traefik.http.middlewares.demo-realestate-platform-headers.headers.stsSeconds=31536000",
                "traefik.http.middlewares.demo-realestate-platform-headers.headers.customResponseHeaders.X-Robots-Tag=all");
        List<String> apiLabels = list(doc.path("services").path("api").path("labels"));
        assertThat(apiLabels).contains(
                "traefik.http.routers.demo-realestate-platform-api.rule=Host(`demos.coach-raffael.com`) && (Path(`/realestate-platform/api`) || PathPrefix(`/realestate-platform/api/`))");
        // /slug/api vor /slug, beide über der Schlafseite des Hubs (10) und unter seiner API (1000)
        int webPrio = priority(webLabels);
        int apiPrio = priority(apiLabels);
        assertThat(apiPrio).isGreaterThan(webPrio);
        assertThat(webPrio).isGreaterThan(10).isLessThan(1000);
        assertThat(apiPrio).isLessThan(1000);
    }

    @Test
    void internalServicesGetNoRouterButNamedVolumes() throws Exception {
        JsonNode doc = render(manifest, "private");
        JsonNode db = doc.path("services").path("db");
        assertThat(db.path("image").asText()).isEqualTo("postgres:16-alpine");
        assertThat(list(db.path("labels"))).containsExactly("coach-raffael.demo=realestate-platform");
        assertThat(list(db.path("volumes"))).containsExactly("db-data:/var/lib/postgresql/data");
        assertThat(doc.path("volumes").has("db-data")).isTrue();
        assertThat(list(db.path("security_opt"))).containsExactly("no-new-privileges:true");
        assertThat(list(doc.path("services").path("web").path("labels")))
                .contains("traefik.http.middlewares.demo-realestate-platform-headers.headers.customResponseHeaders.X-Robots-Tag=noindex, nofollow");
    }

    @Test
    void subdomainRoutingAndEscaping() throws Exception {
        DemoManifest m = new ManifestParser().parse("""
                slug: shop
                title: Shop
                routing: subdomain
                services:
                  web: { image: ghcr.io/coach-raffael/shop, port: 80, expose: /, memory: 64m, env: { GREETING: "pay $5", FLAG: "true" } }
                """);
        String out = renderer.render(m, Map.of("web", "ghcr.io/coach-raffael/shop:abc1234"), "unlisted");
        JsonNode web = yaml.readTree(out).path("services").path("web");
        assertThat(list(web.path("labels"))).contains("traefik.http.routers.demo-shop-web.rule=Host(`shop.demos.coach-raffael.com`)");
        assertThat(list(web.path("environment"))).contains("BASE_PATH=", "GREETING=pay $$5", "FLAG=true",
                "DEMO_PUBLIC_URL=https://shop.demos.coach-raffael.com");
        assertThat(renderer.publicUrl(m)).isEqualTo("https://shop.demos.coach-raffael.com");
    }

    @Test
    void outputPassesTheAgentsForbiddenPatterns() {
        String out = renderer.render(manifest, Map.of("web", WEB, "api", API), "public");
        // Gleiche Regeln wie agent/demo_agent.py FORBIDDEN – der Hub erzeugt nie etwas, das der Agent ablehnt
        assertThat(out).doesNotContainPattern("(?m)^\\s*privileged\\s*:")
                .doesNotContain("docker.sock")
                .doesNotContainPattern("(?m)^\\s*network_mode\\s*:")
                .doesNotContainPattern("(?m)^\\s*(pid|ipc|uts|userns_mode)\\s*:")
                .doesNotContainPattern("(?m)^\\s*cap_add\\s*:")
                .doesNotContainPattern("(?m)^\\s*devices\\s*:")
                .doesNotContainPattern("(?m)^\\s*-\\s*[\"']?(/|\\.|~)")
                .doesNotContainPattern("(?m)^\\s*build\\s*:");
    }

    @Test
    void refusesMissingOrForeignImages() {
        assertThatThrownBy(() -> renderer.render(manifest, Map.of("web", WEB), "private"))
                .hasMessageContaining("api");
        assertThatThrownBy(() -> renderer.render(manifest, Map.of("web", "ghcr.io/evil/x@sha256:1", "api", API), "private"))
                .hasMessageContaining("web");
    }

    @Test
    void agentConfigCarriesResetOnly() throws Exception {
        JsonNode cfg = new ObjectMapper().readTree(renderer.agentConfig(manifest));
        assertThat(cfg.path("slug").asText()).isEqualTo("realestate-platform");
        assertThat(cfg.path("project").asText()).isEqualTo("demo-realestate-platform");
        assertThat(cfg.path("reset").path("service").asText()).isEqualTo("api");
        assertThat(list(cfg.path("reset").path("command"))).containsExactly("java", "-jar", "app.jar", "--reset-demo");
    }

    private static int priority(List<String> labels) {
        return labels.stream().filter(l -> l.contains(".priority=")).map(l -> Integer.parseInt(l.substring(l.indexOf('=') + 1)))
                .findFirst().orElseThrow();
    }
}
