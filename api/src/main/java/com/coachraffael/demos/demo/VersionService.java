package com.coachraffael.demos.demo;

import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.AuditLog;
import com.coachraffael.demos.common.Errors;
import com.coachraffael.demos.manifest.DemoManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Versionen eines Showcases mit Qualitätsgate. Das Gate ist im Hub verbindlich definiert: Eine Version gilt nur als
 * grün, wenn <em>alle</em> Pflichtprüfungen gemeldet und bestanden sind – ein Workflow, der eine Prüfung weglässt,
 * kann sie also nicht „überspringen“.
 */
@Service
public class VersionService {

    static final String SELECT = """
            select id, demo_id, git_sha, images::text as images, gate::text as gate, gate_passed,
                   gate_experience::text as gate_experience, experience_passed, run_url, created_at
            from demo_versions""";

    /** Pflichtprüfungen des Build-Gates (build-showcase.yml). */
    public static final Set<String> BUILD_CHECKS = Set.of("build", "tests", "lint", "secrets", "dependencies",
            "image-scan", "readme", "license");
    /** Pflichtprüfungen gegen die laufende Demo (deploy-demo.yml). */
    public static final Set<String> EXPERIENCE_CHECKS = Set.of("smoke", "flows", "console", "links", "lighthouse",
            "headers", "mobile");

    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{7,40}$");
    private static final Pattern RUN_URL = Pattern.compile("^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/actions/runs/\\d+(/.*)?$");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final DemoService demos;
    private final AuditLog audit;
    private final DemoEvents events;

    public VersionService(JdbcClient jdbc, ObjectMapper json, DemoService demos, AuditLog audit, DemoEvents events) {
        this.jdbc = jdbc;
        this.json = json;
        this.demos = demos;
        this.audit = audit;
        this.events = events;
    }

    public record BuildReport(String repo, String slug, String sha, Map<String, String> images, JsonNode checks,
                              String runUrl) {
    }

    public record GateCheck(String name, boolean ok, String detail) {
    }

    /** Ergebnis einer Gate-Auswertung: bestanden, und welche Pflichtprüfungen fehlen bzw. rot sind. */
    public record GateResult(boolean passed, List<String> missing, List<String> failed, List<GateCheck> checks) {
    }

    @Transactional(readOnly = true)
    public List<DemoVersion> list(long demoId, int limit) {
        return jdbc.sql(SELECT + " where demo_id = :d order by created_at desc, id desc limit :l")
                .param("d", demoId).param("l", Math.max(1, Math.min(limit, 200))).query(DemoVersion.class).list();
    }

    @Transactional(readOnly = true)
    public DemoVersion get(long id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(DemoVersion.class).optional()
                .orElseThrow(() -> Errors.notFound("Version", id));
    }

    @Transactional(readOnly = true)
    public Optional<DemoVersion> bySha(long demoId, String sha) {
        return jdbc.sql(SELECT + " where demo_id = :d and git_sha like :s order by created_at desc limit 1")
                .param("d", demoId).param("s", sha + "%").query(DemoVersion.class).optional();
    }

    @Transactional(readOnly = true)
    public Optional<DemoVersion> latestGreen(long demoId) {
        return jdbc.sql(SELECT + " where demo_id = :d and gate_passed order by created_at desc, id desc limit 1")
                .param("d", demoId).query(DemoVersion.class).optional();
    }

    /**
     * Build aus GitHub Actions melden. Idempotent je Commit: derselbe SHA liefert die vorhandene Version.
     * Prüft, dass das meldende Repo das registrierte ist und die Images zu den eigenen Diensten aus demo.yaml passen.
     */
    @Transactional
    public DemoVersion recordBuild(BuildReport r, Actor actor) {
        Demo d = demos.get(r.slug());
        if (!d.managed() || d.sourceRepo() == null || !d.sourceRepo().equalsIgnoreCase(r.repo())) {
            throw new Errors.Conflict("Repo " + r.repo() + " darf für " + r.slug() + " keine Builds melden");
        }
        if (r.sha() == null || !SHA.matcher(r.sha()).matches()) {
            throw new IllegalArgumentException("sha: Commit-SHA erwartet");
        }
        if (r.runUrl() != null && !RUN_URL.matcher(r.runUrl()).matches()) {
            throw new IllegalArgumentException("runUrl: Link auf einen Actions-Lauf erwartet");
        }
        Optional<DemoVersion> existing = bySha(d.id(), r.sha());
        if (existing.isPresent() && existing.get().gitSha().equals(r.sha())) {
            return existing.get();
        }
        DemoManifest m = demos.manifest(d.slug());
        GateResult gate = evaluate(r.checks(), BUILD_CHECKS);
        // Rote Builds dürfen unvollständig sein (Build eines Dienstes gescheitert) – sie werden trotzdem
        // festgehalten, damit sichtbar ist, was fehlt. Grün heißt: jedes eigene Image ist da.
        Map<String, String> images = checkImages(m, r.images(), r.sha(), gate.passed());
        long id = jdbc.sql("""
                        insert into demo_versions (demo_id, git_sha, images, gate, gate_passed, run_url)
                        values (:d, :sha, cast(:img as jsonb), cast(:gate as jsonb), :ok, :run) returning id""")
                .param("d", d.id()).param("sha", r.sha()).param("img", write(images)).param("gate", write(gateJson(gate)))
                .param("ok", gate.passed()).param("run", r.runUrl()).query(Long.class).single();
        audit.record(actor, "version.build", "demo_version", id, null,
                Map.of("slug", d.slug(), "sha", r.sha(), "gatePassed", gate.passed(), "failed", gate.failed(),
                        "missing", gate.missing()));
        events.record(d.id(), "build", actor, gate.passed(), Map.of("sha", r.sha(), "failed", gate.failed(),
                "missing", gate.missing()));
        return get(id);
    }

    /** Erlebnis-Prüfung gegen die laufende Demo melden – je Version genau einmal. */
    @Transactional
    public DemoVersion recordExperience(String slug, long versionId, JsonNode checks, Actor actor) {
        Demo d = demos.get(slug);
        DemoVersion v = get(versionId);
        if (v.demoId() != d.id()) {
            throw new Errors.Conflict("Version " + versionId + " gehört nicht zu " + slug);
        }
        if (v.experiencePassed() != null) {
            return v;
        }
        GateResult gate = evaluate(checks, EXPERIENCE_CHECKS);
        jdbc.sql("update demo_versions set gate_experience = cast(:g as jsonb), experience_passed = :ok where id = :id")
                .param("g", write(gateJson(gate))).param("ok", gate.passed()).param("id", versionId).update();
        audit.record(actor, "version.experience", "demo_version", versionId, null,
                Map.of("slug", slug, "passed", gate.passed(), "failed", gate.failed(), "missing", gate.missing()));
        events.record(d.id(), "experience", actor, gate.passed(), Map.of("sha", v.gitSha(), "failed", gate.failed(),
                "missing", gate.missing()));
        return get(versionId);
    }

    /** Wertet gemeldete Prüfungen gegen die Pflichtliste aus. Zusätzliche Prüfungen zählen mit, falls rot. */
    public static GateResult evaluate(JsonNode checks, Set<String> required) {
        Map<String, GateCheck> byName = new LinkedHashMap<>();
        if (checks != null && checks.isArray()) {
            for (JsonNode c : checks) {
                String name = c.path("name").asText("").trim();
                if (name.isEmpty() || name.length() > 60) continue;
                String detail = c.path("detail").asText("");
                if (detail.length() > 2000) detail = detail.substring(0, 2000);
                // Mehrfach gemeldet: eine rote Meldung gewinnt
                GateCheck prev = byName.get(name);
                boolean ok = c.path("ok").isBoolean() && c.path("ok").asBoolean() && (prev == null || prev.ok());
                byName.put(name, new GateCheck(name, ok, detail));
            }
        }
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(byName.keySet());
        List<String> failed = byName.values().stream().filter(c -> !c.ok()).map(GateCheck::name).sorted().toList();
        return new GateResult(missing.isEmpty() && failed.isEmpty(), List.copyOf(missing), failed,
                List.copyOf(byName.values()));
    }

    private ObjectNode gateJson(GateResult g) {
        ObjectNode o = json.createObjectNode();
        o.put("passed", g.passed());
        o.set("missing", json.valueToTree(g.missing()));
        o.set("failed", json.valueToTree(g.failed()));
        ArrayNode arr = o.putArray("checks");
        g.checks().forEach(c -> arr.add(json.valueToTree(c)));
        return o;
    }

    /** Je eigenem Dienst genau ein Image aus demselben Repository-Pfad, festgelegt per Digest oder Commit-Tag. */
    static Map<String, String> checkImages(DemoManifest m, Map<String, String> given, String sha, boolean requireAll) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> g = given == null ? Map.of() : given;
        for (var e : m.services().entrySet()) {
            if (!e.getValue().ownImage()) continue;
            String ref = g.get(e.getKey());
            String base = e.getValue().image();
            if (ref == null) {
                if (!requireAll) continue;
                throw new IllegalArgumentException("images." + e.getKey() + " fehlt");
            }
            boolean digest = ref.matches(Pattern.quote(base) + "@sha256:[0-9a-f]{64}");
            boolean tag = ref.equals(base + ":" + sha);
            if (!digest && !tag) {
                throw new IllegalArgumentException("images." + e.getKey() + ": erwartet " + base + "@sha256:… oder "
                        + base + ":" + sha);
            }
            out.put(e.getKey(), ref);
        }
        for (String k : g.keySet()) {
            if (!out.containsKey(k) && !(m.services().containsKey(k) && m.services().get(k).ownImage())) {
                throw new IllegalArgumentException("images." + k + ": kein eigener Dienst in demo.yaml");
            }
        }
        return out;
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
