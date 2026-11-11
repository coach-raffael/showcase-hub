package com.coachraffael.demos;

import com.coachraffael.demos.agent.AgentClient;
import com.coachraffael.demos.agent.LifecycleService;
import com.coachraffael.demos.catalog.CatalogService;
import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.Errors;
import com.coachraffael.demos.deploy.DeployService;
import com.coachraffael.demos.demo.Demo;
import com.coachraffael.demos.demo.DemoService;
import com.coachraffael.demos.demo.DemoVersion;
import com.coachraffael.demos.demo.SecretService;
import com.coachraffael.demos.demo.ShareLinkService;
import com.coachraffael.demos.demo.VersionService;
import com.coachraffael.demos.github.GithubClient;
import com.coachraffael.demos.manifest.ManifestException;
import com.coachraffael.demos.mcp.McpGuard;
import com.coachraffael.demos.mcp.PendingActionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Der ganze Weg einer Demo gegen echtes Postgres: registrieren → roter Build → grüner Build → automatisch
 * ausrollen → Bündel → live → Erlebnis-Gate → öffentlich → Katalog; Start/Stopp/Wecken; MCP-Richtlinie.
 * GitHub und demo-agent sind gemockt.
 */
@EnabledIf("com.coachraffael.demos.DatabaseTest#available")
@SpringBootTest
class HubFlowTest extends DatabaseTest {

    static final Actor UI = new Actor("raffael", "ui");
    static final Actor CI = new Actor("github-actions", "ci");
    static final Actor MCP = new Actor("claude", "mcp");

    @MockitoBean
    GithubClient github;
    @MockitoBean
    AgentClient agent;

    @Autowired DemoService demos;
    @Autowired VersionService versions;
    @Autowired DeployService deploy;
    @Autowired SecretService secrets;
    @Autowired ShareLinkService shareLinks;
    @Autowired LifecycleService lifecycle;
    @Autowired CatalogService catalog;
    @Autowired McpGuard guard;
    @Autowired PendingActionService pending;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    String slug;
    String repo;

    @BeforeEach
    void setUp() {
        slug = "t-" + UUID.randomUUID().toString().substring(0, 8);
        repo = "coach-raffael/" + slug;
        when(github.fetchFile(eq(repo), eq("demo.yaml"), anyString())).thenReturn(manifest(slug));
    }

    static String manifest(String slug) {
        return """
                slug: %s
                title: { en: "Test Shop", de: "Testladen" }
                teaser: { en: "A shop to test the hub" }
                tags: [nextjs, test]
                visibility: public
                services:
                  web: { image: ghcr.io/coach-raffael/%s-web, port: 3000, expose: /, memory: 256m, health: /health }
                  db: { image: postgres:16-alpine, memory: 128m, volumes: [/var/lib/postgresql/data] }
                reset: { service: web, command: [node, reset.js] }
                env_required: [ADMIN_PASSWORD]
                quality:
                  flows: [e2e/buy.spec.ts]
                """.formatted(slug, slug);
    }

    String sha(char c) {
        return String.valueOf(c).repeat(40);
    }

    JsonNode checks(boolean ok, java.util.Set<String> names) {
        var arr = json.createArrayNode();
        names.forEach(n -> arr.addObject().put("name", n).put("ok", ok).put("detail", "test"));
        return arr;
    }

    DemoVersion build(char c, boolean green) {
        String image = "ghcr.io/coach-raffael/" + slug + "-web@sha256:" + String.valueOf(c).repeat(64);
        return versions.recordBuild(new VersionService.BuildReport(repo, slug, sha(c), Map.of("web", image),
                checks(green, VersionService.BUILD_CHECKS), null), CI);
    }

    @Test
    void fullLifecycleFromRegistrationToPublicCatalog() {
        // Registrieren: privat, Wunsch „public“ wird nur notiert
        var reg = demos.register(repo, null, null, MCP);
        assertThat(reg.created()).isTrue();
        assertThat(reg.demo().visibility()).isEqualTo("private");
        assertThat(reg.demo().state()).isEqualTo("new");
        assertThat(reg.demo().url()).isEqualTo("https://demos.example.test/" + slug);
        assertThat(reg.notes()).anySatisfy(n -> assertThat(n).contains("visibility: public"));

        // Roter Build: gespeichert, aber nichts wird automatisch ausgerollt
        DemoVersion red = build('a', false);
        assertThat(red.gatePassed()).isFalse();
        assertThat(deploy.afterBuild(red, slug)).isFalse();
        assertThatThrownBy(() -> deploy.deploy(slug, null, UI)).hasMessageContaining("grünem Gate");
        assertThatThrownBy(() -> deploy.deploy(slug, red.gitSha(), MCP)).hasMessageContaining("nur aus der Oberfläche");

        // Grüner Build – aber Secret fehlt: Ausrollen scheitert früh, nicht erst im Workflow
        DemoVersion green = build('b', true);
        assertThat(green.gatePassed()).isTrue();
        assertThatThrownBy(() -> deploy.afterBuild(green, slug)).hasMessageContaining("ADMIN_PASSWORD");
        verify(github, never()).dispatchWorkflow(anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> secrets.set(slug, "ADMIN_PASSWORD", "s3cret", MCP)).hasMessageContaining("Oberfläche");
        secrets.set(slug, "ADMIN_PASSWORD", "s3cret", UI);
        assertThat(secrets.list(slug)).singleElement().satisfies(s -> assertThat(s.set()).isTrue());

        assertThat(deploy.afterBuild(green, slug)).isTrue();
        verify(github).dispatchWorkflow(eq("coach-raffael/showcase-hub"), eq("deploy-demo.yml"), eq("main"),
                argThat(m -> slug.equals(m.get("slug")) && String.valueOf(green.id()).equals(m.get("version_id"))));
        assertThat(demos.get(slug).state()).isEqualTo("deploying");

        // Bündel für den Workflow
        DeployService.Bundle b = deploy.bundle(slug, green.id());
        assertThat(b.compose()).contains("ghcr.io/coach-raffael/" + slug + "-web@sha256:" + "b".repeat(64));
        assertThat(b.compose()).contains("PathPrefix(`/" + slug + "/`)");
        assertThat(b.env()).contains("ADMIN_PASSWORD='s3cret'");
        assertThat(b.smokeUrl()).isEqualTo("https://demos.example.test/" + slug + "/health");
        assertThat(b.flows()).containsExactly("e2e/buy.spec.ts");
        assertThat(b.agentConfig()).contains("\"project\" : \"demo-" + slug + "\"");

        // Live
        Demo live = deploy.recordResult(slug, green.id(), true, "ok", null, CI);
        assertThat(live.state()).isEqualTo("running");
        assertThat(live.currentVersionId()).isEqualTo(green.id());

        // Öffentlich erst nach dem Erlebnis-Gate
        assertThatThrownBy(() -> demos.setVisibility(slug, "public", UI)).hasMessageContaining("steht noch aus");
        versions.recordExperience(slug, green.id(), checks(false, VersionService.EXPERIENCE_CHECKS), CI);
        assertThatThrownBy(() -> demos.setVisibility(slug, "public", UI)).hasMessageContaining("rot");
        // Erlebnis-Ergebnis ist festgeschrieben – ein zweiter Bericht ändert nichts
        DemoVersion again = versions.recordExperience(slug, green.id(), checks(true, VersionService.EXPERIENCE_CHECKS), CI);
        assertThat(again.experiencePassed()).isFalse();

        // Neuer grüner Build mit grünem Erlebnis → öffentlich
        DemoVersion v2 = build('c', true);
        deploy.recordResult(slug, v2.id(), true, "ok", null, CI);
        versions.recordExperience(slug, v2.id(), checks(true, VersionService.EXPERIENCE_CHECKS), CI);
        assertThat(demos.setVisibility(slug, "public", UI).visibility()).isEqualTo("public");

        var entry = catalog.feed().entries().stream().filter(e -> e.slug().equals(slug)).findFirst().orElseThrow();
        assertThat(entry.verified()).isTrue();
        assertThat(entry.title()).containsEntry("en", "Test Shop");
        assertThat(entry.url()).isEqualTo("https://demos.example.test/" + slug);
        assertThat(catalog.feed().entries()).anySatisfy(e -> assertThat(e.slug()).isEqualTo("langtutor"));

        // Öffentliche Demos bekommen keine roten Versionen mehr – auch nicht aus der Oberfläche
        DemoVersion red2 = build('d', false);
        assertThatThrownBy(() -> deploy.deploy(slug, red2.gitSha(), UI)).hasMessageContaining("öffentliche Demos");
    }

    @Test
    void lifecycleAndWaking() {
        demos.register(repo, null, null, UI);
        assertThatThrownBy(() -> lifecycle.control(slug, AgentClient.Action.start, UI)).hasMessageContaining("noch nie ausgerollt");

        DemoVersion v = build('a', true);
        deploy.recordResult(slug, v.id(), true, null, null, CI);
        demos.setVisibility(slug, "unlisted", UI);

        lifecycle.control(slug, AgentClient.Action.stop, UI);
        verify(agent).run(slug, AgentClient.Action.stop);
        assertThat(demos.get(slug).state()).isEqualTo("stopped");

        // Besucher weckt; zweiter Klick innerhalb von 20 s startet nicht noch einmal
        jdbc.sql("update demos set last_woken_at = now() - interval '1 hour' where slug = :s").param("s", slug).update();
        assertThat(lifecycle.wake(slug, null)).isEqualTo(LifecycleService.WakeResult.starting);
        assertThat(demos.get(slug).state()).isEqualTo("running");
        demos.setState(demos.get(slug).id(), "stopped");
        assertThat(lifecycle.wake(slug, null)).isEqualTo(LifecycleService.WakeResult.starting);
        verify(agent, times(1)).run(slug, AgentClient.Action.start);

        // Nur mit Share-Link
        demos.setLifecycle(slug, null, null, null, "share_link", null, UI);
        jdbc.sql("update demos set last_woken_at = now() - interval '1 hour' where slug = :s").param("s", slug).update();
        assertThatThrownBy(() -> lifecycle.wake(slug, null)).hasMessageContaining("persönlichen Link");
        assertThatThrownBy(() -> lifecycle.wake(slug, "s_wrong")).hasMessageContaining("persönlichen Link");
        var link = shareLinks.create(slug, "ACME GmbH", "lead-42", 30, UI);
        assertThat(link.url()).startsWith("https://demos.example.test/" + slug + "?s=s_");
        assertThat(lifecycle.wake(slug, link.token())).isEqualTo(LifecycleService.WakeResult.starting);
        assertThat(shareLinks.list(slug)).singleElement().satisfies(l -> assertThat(l.uses()).isEqualTo(1));

        // Private Demos gibt es für Besucher nicht
        demos.setVisibility(slug, "private", UI);
        assertThatThrownBy(() -> lifecycle.wake(slug, link.token())).isInstanceOf(Errors.NotFound.class);

        // Leerlauf: on-demand schläft nach idle_minutes
        demos.setLifecycle(slug, "on-demand", null, 5, "anyone", null, UI);
        demos.setState(demos.get(slug).id(), "running");
        jdbc.sql("update demos set last_woken_at = now() - interval '10 minutes' where slug = :s").param("s", slug).update();
        clearInvocations(agent);
        when(agent.configured()).thenReturn(true);
        when(agent.statusAll()).thenReturn(List.of());
        lifecycle.housekeeping();
        verify(agent).run(slug, AgentClient.Action.stop);
        assertThat(demos.get(slug).state()).isEqualTo("stopped");
    }

    @Test
    void ttlExpiresAndCanBeExtended() {
        demos.register(repo, null, null, UI);
        DemoVersion v = build('a', true);
        deploy.recordResult(slug, v.id(), true, null, null, CI);
        demos.setLifecycle(slug, "ttl", 3, null, null, null, UI);
        assertThat(demos.get(slug).expiresAt()).isNotNull();
        jdbc.sql("update demos set expires_at = now() - interval '1 minute' where slug = :s").param("s", slug).update();
        when(agent.configured()).thenReturn(true);
        when(agent.statusAll()).thenReturn(List.of());
        lifecycle.housekeeping();
        assertThat(demos.get(slug).state()).isEqualTo("expired");
        assertThatThrownBy(() -> lifecycle.control(slug, AgentClient.Action.start, UI)).hasMessageContaining("verlängern");
        demos.extendTtl(slug, 7, UI);
        lifecycle.control(slug, AgentClient.Action.start, UI);
        assertThat(demos.get(slug).state()).isEqualTo("running");
    }

    @Test
    void mcpPolicyRoutesVisibilityThroughApproval() {
        demos.register(repo, null, null, UI);
        DemoVersion v = build('a', true);
        deploy.recordResult(slug, v.id(), true, null, null, CI);
        versions.recordExperience(slug, v.id(), checks(true, VersionService.EXPERIENCE_CHECKS), CI);

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) guard.write("set_visibility",
                Map.of("slug", slug, "visibility", "public"), slug + " → public", "vis-" + slug);
        assertThat(r).containsEntry("status", "pending_approval");
        assertThat(demos.get(slug).visibility()).isEqualTo("private");

        long id = ((Number) r.get("pendingActionId")).longValue();
        var done = pending.approve(id, UI);
        assertThat(done.status()).isEqualTo("executed");
        assertThat(demos.get(slug).visibility()).isEqualTo("public");

        // ALLOW: sofort
        guard.write("update_catalog", Map.of("slug", slug, "sortOrder", 5, "featured", true), "Katalog", null);
        assertThat(demos.get(slug).sortOrder()).isEqualTo(5);
        assertThat(demos.get(slug).featured()).isTrue();
    }

    @Test
    void guardsAgainstForeignReposAndTampering() {
        demos.register(repo, null, null, UI);

        // Anderes Repo darf weder Builds melden noch den Slug übernehmen
        assertThatThrownBy(() -> versions.recordBuild(new VersionService.BuildReport("coach-raffael/other", slug, sha('a'),
                Map.of(), checks(true, VersionService.BUILD_CHECKS), null), CI)).hasMessageContaining("keine Builds");
        when(github.fetchFile(eq("coach-raffael/other"), eq("demo.yaml"), anyString())).thenReturn(manifest(slug));
        assertThatThrownBy(() -> demos.register("coach-raffael/other", null, null, UI)).hasMessageContaining("gehört schon");

        // Kaputtes Manifest wird mit allen Problemen abgewiesen
        when(github.fetchFile(eq(repo), eq("broken.yaml"), anyString())).thenReturn("slug: x\nservices: {}\n");
        assertThatThrownBy(() -> demos.register(repo, "broken.yaml", null, UI)).isInstanceOf(ManifestException.class)
                .hasMessageContaining("title.en fehlt").hasMessageContaining("mindestens ein Dienst");

        // Build-Ergebnisse sind unveränderlich, das Audit-Log nur anfügbar
        DemoVersion v = build('a', false);
        assertThatThrownBy(() -> jdbc.sql("update demo_versions set gate_passed = true where id = :id").param("id", v.id()).update())
                .hasMessageContaining("unveränderlich");
        assertThatThrownBy(() -> jdbc.sql("delete from audit_log").update()).hasMessageContaining("nur anfügbar");

        // Reservierte Slugs auch in der Datenbank
        assertThatThrownBy(() -> jdbc.sql("""
                insert into demos (slug, kind, external_url, title) values ('api', 'external', 'https://x.test', '{"en":"x"}')""")
                .update()).isNotNull();
    }

    @Test
    void ciReportsRereadManifestAtTheBuiltCommit() {
        demos.register(repo, null, null, UI);
        String changed = manifest(slug).replace("Test Shop", "Test Shop v2");
        when(github.fetchFile(repo, "demo.yaml", sha('e'))).thenReturn(changed);
        demos.register(repo, "demo.yaml", sha('e'), CI);
        assertThat(demos.get(slug).title()).containsEntry("en", "Test Shop v2");
    }
}
