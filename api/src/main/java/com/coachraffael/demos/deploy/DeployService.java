package com.coachraffael.demos.deploy;

import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.AuditLog;
import com.coachraffael.demos.common.Errors;
import com.coachraffael.demos.common.HubProperties;
import com.coachraffael.demos.compose.ComposeRenderer;
import com.coachraffael.demos.demo.Demo;
import com.coachraffael.demos.demo.DemoEvents;
import com.coachraffael.demos.demo.DemoService;
import com.coachraffael.demos.demo.DemoVersion;
import com.coachraffael.demos.demo.SecretService;
import com.coachraffael.demos.demo.VersionService;
import com.coachraffael.demos.manifest.DemoManifest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ausrollen läuft über GitHub Actions (Workflow {@code deploy-demo.yml} im Hub-Repo): Der Hub startet den Workflow,
 * der Workflow holt sich das Bündel (compose.yml, .env, agent.json) hier ab, rollt per SSH aus, prüft und meldet
 * zurück. So bleibt jedes Ausrollen ein nachvollziehbarer Actions-Lauf.
 *
 * <p>Regel „letzte grüne Version bleibt live“: Automatisch und für öffentliche Demos werden nur Versionen mit grünem
 * Build-Gate ausgerollt. Eine rote Version lässt sich nur aus der Oberfläche und nur für nicht-öffentliche Demos
 * ausrollen (zum Fehlersuchen).
 */
@Service
public class DeployService {

    public static final String WORKFLOW = "deploy-demo.yml";

    public record Bundle(String slug, long versionId, String sha, String repo, String compose, String env,
                         String agentConfig, String publicUrl, String smokeUrl, List<String> flows,
                         Map<String, Integer> lighthouse, String visibility) {
    }

    private final DemoService demos;
    private final VersionService versions;
    private final SecretService secrets;
    private final ComposeRenderer renderer;
    private final com.coachraffael.demos.github.GithubClient github;
    private final HubProperties props;
    private final AuditLog audit;
    private final DemoEvents events;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DeployService(DemoService demos, VersionService versions, SecretService secrets, ComposeRenderer renderer,
                         com.coachraffael.demos.github.GithubClient github, HubProperties props, AuditLog audit,
                         DemoEvents events, JdbcClient jdbc, ObjectMapper json) {
        this.demos = demos;
        this.versions = versions;
        this.secrets = secrets;
        this.renderer = renderer;
        this.github = github;
        this.props = props;
        this.audit = audit;
        this.events = events;
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param sha Commit (auch gekürzt) oder null für die neueste grüne Version
     */
    @Transactional
    public Map<String, Object> deploy(String slug, String sha, Actor actor) {
        Demo d = demos.get(slug);
        if (!d.managed()) {
            throw new Errors.Conflict(slug + " ist ein externer Katalogeintrag");
        }
        DemoVersion v = (sha == null || sha.isBlank())
                ? versions.latestGreen(d.id()).orElseThrow(() -> new Errors.Conflict(
                        "Keine Version mit grünem Gate für " + slug + " – erst einen grünen Build melden"))
                : versions.bySha(d.id(), sha.trim()).orElseThrow(() -> Errors.notFound("Version", sha));
        if (!v.gatePassed()) {
            if ("public".equals(d.visibility())) {
                throw new Errors.Conflict("Version " + v.gitSha() + " hat ein rotes Gate – öffentliche Demos bekommen nur grüne Versionen");
            }
            if (!"ui".equals(actor.channel())) {
                throw new Errors.Conflict("Rote Versionen nur aus der Oberfläche ausrollen (Fehlersuche)");
            }
        }
        // Früh scheitern statt im Workflow: Secrets vollständig, compose erzeugbar
        DemoManifest m = demos.manifest(slug);
        secrets.resolve(d, m.envRequired());
        renderer.render(withRouting(m, d.routing()), images(v), d.visibility());

        demos.setState(d.id(), "deploying");
        Map<String, String> inputs = Map.of("slug", slug, "version_id", String.valueOf(v.id()), "sha", v.gitSha());
        github.dispatchWorkflow(props.github().hubRepo(), WORKFLOW, "main", inputs);
        audit.record(actor, "demo.deploy", "demo", d.id(), null, Map.of("sha", v.gitSha(), "versionId", v.id()));
        events.record(d.id(), "deploy.requested", actor, true, Map.of("sha", v.gitSha()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "deploying");
        out.put("slug", slug);
        out.put("sha", v.gitSha());
        out.put("gatePassed", v.gatePassed());
        out.put("message", "Ausrollen über GitHub Actions gestartet (" + props.github().hubRepo() + " → " + WORKFLOW + ")");
        out.put("actions", "https://github.com/" + props.github().hubRepo() + "/actions/workflows/" + WORKFLOW);
        return out;
    }

    /** Nach einem gemeldeten Build: grün + auto_deploy → ausrollen. Gibt zurück, ob ausgerollt wird. */
    public boolean afterBuild(DemoVersion v, String slug) {
        Demo d = demos.get(slug);
        if (!v.gatePassed() || !d.autoDeploy() || "expired".equals(d.state())) {
            return false;
        }
        deploy(slug, v.gitSha(), Actor.system());
        return true;
    }

    /** Das Bündel für den Workflow – enthält entschlüsselte Secrets, daher nur für den Bereich {@code ci}. */
    @Transactional(readOnly = true)
    public Bundle bundle(String slug, long versionId) {
        Demo d = demos.get(slug);
        DemoVersion v = versions.get(versionId);
        if (v.demoId() != d.id()) {
            throw new Errors.Conflict("Version " + versionId + " gehört nicht zu " + slug);
        }
        DemoManifest m = withRouting(demos.manifest(slug), d.routing());
        Map<String, String> env = secrets.resolve(d, m.envRequired());
        StringBuilder envFile = new StringBuilder("# Erzeugt vom Showcase-Hub – nicht von Hand ändern\n");
        env.forEach((k, val) -> envFile.append(k).append("='").append(val).append("'\n"));
        String publicUrl = renderer.publicUrl(m);
        String smoke = publicUrl + ("/".equals(m.smokePath()) ? "/" : m.smokePath());
        return new Bundle(slug, v.id(), v.gitSha(), d.sourceRepo(), renderer.render(m, images(v), d.visibility()),
                envFile.toString(), renderer.agentConfig(m), publicUrl, smoke, m.quality().flows(),
                m.quality().lighthouse(), d.visibility());
    }

    /** Ergebnis des Workflows: ok → diese Version ist live. */
    @Transactional
    public Demo recordResult(String slug, long versionId, boolean ok, String detail, String runUrl, Actor actor) {
        Demo d = demos.get(slug);
        DemoVersion v = versions.get(versionId);
        if (v.demoId() != d.id()) {
            throw new Errors.Conflict("Version " + versionId + " gehört nicht zu " + slug);
        }
        if (ok) {
            jdbc.sql("""
                            update demos set current_version_id = :v, state = 'running', state_at = now(),
                                   last_woken_at = now(),
                                   expires_at = case when lifecycle = 'ttl'
                                                     then coalesce(expires_at, now() + make_interval(days => ttl_days))
                                                     else expires_at end,
                                   updated_at = now()
                            where id = :id""")
                    .param("v", versionId).param("id", d.id()).update();
        } else {
            // Die bisherige Version bleibt die aktuelle; Zustand zeigt den Fehlschlag
            demos.setState(d.id(), "failed");
        }
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("sha", v.gitSha());
        if (detail != null) det.put("detail", detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        if (runUrl != null) det.put("run", runUrl);
        events.record(d.id(), ok ? "deploy.done" : "deploy.failed", actor, ok, det);
        audit.record(actor, ok ? "demo.deployed" : "demo.deploy_failed", "demo", d.id(), null, det);
        return demos.get(slug);
    }

    private Map<String, String> images(DemoVersion v) {
        try {
            return json.readValue(v.images(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("images der Version " + v.id() + " nicht lesbar", e);
        }
    }

    /** Routing im Hub hat Vorrang vor demo.yaml (Umstellung nur über change_routing). */
    static DemoManifest withRouting(DemoManifest m, String routing) {
        if (routing.equals(m.routing())) return m;
        return new DemoManifest(m.slug(), m.title(), m.teaser(), m.description(), m.category(), m.tags(),
                m.visibility(), m.lifecycle(), m.ttlDays(), m.idleMinutes(), routing, m.wake(), m.services(),
                m.reset(), m.envRequired(), m.quality());
    }
}
