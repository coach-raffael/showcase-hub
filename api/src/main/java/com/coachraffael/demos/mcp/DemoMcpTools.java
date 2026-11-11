package com.coachraffael.demos.mcp;

import com.coachraffael.demos.agent.LifecycleService;
import com.coachraffael.demos.demo.Demo;
import com.coachraffael.demos.demo.DemoEvents;
import com.coachraffael.demos.demo.DemoService;
import com.coachraffael.demos.demo.DemoVersion;
import com.coachraffael.demos.demo.VersionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP-Tools des Showcase-Hubs. Lesen prüft die Richtlinie „read“; jede Änderung läuft über
 * {@link McpGuard#write} – je nach Richtlinie sofort, als Freigabe oder gar nicht. Secrets gibt es per MCP weder
 * zu lesen noch zu setzen.
 */
@Component
public class DemoMcpTools {

    private final McpGuard guard;
    private final DemoService demos;
    private final VersionService versions;
    private final DemoEvents events;
    private final LifecycleService lifecycle;

    public DemoMcpTools(McpGuard guard, DemoService demos, VersionService versions, DemoEvents events,
                        LifecycleService lifecycle) {
        this.guard = guard;
        this.demos = demos;
        this.versions = versions;
        this.events = events;
        this.lifecycle = lifecycle;
    }

    // ---------- Lesen ----------

    @Tool(name = "list_demos", description = """
            Alle Einträge des Hubs: verwaltete Demos und externe Katalogeinträge mit Zustand, Sichtbarkeit,
            Lebenszyklus, Adresse und Gate-Status der ausgerollten Version.""")
    public List<Map<String, Object>> listDemos() {
        guard.checkRead();
        return demos.list().stream().map(this::brief).toList();
    }

    @Tool(name = "get_demo", description = """
            Details einer Demo: Katalog, Lebenszyklus, aktuelle Version mit Gate, letzte Versionen und Ereignisse,
            plus Link in den Hub.""")
    public Map<String, Object> getDemo(@ToolParam(description = "Slug, z. B. realestate-platform") String slug) {
        guard.checkRead();
        Demo d = demos.get(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("demo", d);
        out.put("currentVersion", demos.currentVersion(d).orElse(null));
        if (d.managed()) {
            out.put("versions", versions.list(d.id(), 10));
        }
        out.put("events", events.list(d.id(), 20));
        out.put("hub", guard.url("/admin/" + slug));
        return out;
    }

    @Tool(name = "get_quality_report", description = """
            Qualitätsgate einer Version (Standard: neueste): Build-Prüfungen (build, tests, lint, secrets,
            dependencies, image-scan, readme, license) und Erlebnis-Prüfungen gegen die laufende Demo (smoke, flows,
            console, links, lighthouse, headers, mobile). Nennt fehlende und rote Prüfungen.""")
    public Map<String, Object> qualityReport(@ToolParam(description = "Slug") String slug,
                                             @ToolParam(description = "Commit-SHA (auch gekürzt); leer = neueste Version", required = false) String sha) {
        guard.checkRead();
        Demo d = demos.get(slug);
        DemoVersion v = (sha == null || sha.isBlank())
                ? versions.list(d.id(), 1).stream().findFirst()
                        .orElseThrow(() -> new IllegalStateException("Noch kein Build gemeldet"))
                : versions.bySha(d.id(), sha).orElseThrow(() -> new IllegalStateException("Version " + sha + " unbekannt"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sha", v.gitSha());
        out.put("buildPassed", v.gatePassed());
        out.put("experiencePassed", v.experiencePassed());
        out.put("publishable", v.publishable());
        out.put("build", v.gate());
        out.put("experience", v.gateExperience());
        out.put("run", v.runUrl());
        out.put("required", Map.of("build", VersionService.BUILD_CHECKS, "experience", VersionService.EXPERIENCE_CHECKS));
        return out;
    }

    @Tool(name = "get_demo_status", description = "Zustand der Container einer Demo laut Docker (Status, Health, RAM, CPU).")
    public Object status(@ToolParam(description = "Slug") String slug) {
        guard.checkRead();
        return lifecycle.status(slug);
    }

    @Tool(name = "get_demo_logs", description = "Letzte Log-Zeilen aller Container einer Demo (höchstens 500).")
    public String logs(@ToolParam(description = "Slug") String slug,
                       @ToolParam(description = "Anzahl Zeilen, Standard 200", required = false) Integer tail) {
        guard.checkRead();
        return lifecycle.logs(slug, tail == null ? 200 : tail);
    }

    // ---------- Schreiben ----------

    @Tool(name = "register_demo", description = """
            Showcase registrieren oder demo.yaml neu einlesen: Repo nennen (owner/repo), der Hub liest demo.yaml
            selbst und prüft den Demo-Vertrag. Neue Demos starten privat. Fehler im Manifest werden einzeln genannt.""")
    public Object register(@ToolParam(description = "Repo, z. B. coach-raffael/realestate-platform") String repo,
                           @ToolParam(description = "Pfad zu demo.yaml, Standard demo.yaml (Sammel-Repo: ordner/demo.yaml)", required = false) String path,
                           @ToolParam(description = "Branch oder Commit, Standard main", required = false) String ref) {
        return guard.write("register_demo", args("repo", repo, "path", path, "ref", ref), "Registrieren " + repo, null);
    }

    @Tool(name = "upsert_external_entry", description = """
            Externer Katalogeintrag ohne Steuerung (z. B. eine eigene Domain wie langtutor.cloud): Link, Titel,
            Teaser. Titel und Teaser auf Englisch (en), optional Deutsch (de).""")
    public Object external(@ToolParam(description = "Slug") String slug,
                           @ToolParam(description = "https-Adresse") String url,
                           @ToolParam(description = "Titel je Sprache, mindestens en") Map<String, String> title,
                           @ToolParam(description = "Teaser je Sprache", required = false) Map<String, String> teaser,
                           @ToolParam(description = "showcase, demo oder tool", required = false) String category,
                           @ToolParam(description = "Tags", required = false) List<String> tags) {
        return guard.write("upsert_external", args("slug", slug, "url", url, "title", title, "teaser", teaser,
                "category", category, "tags", tags), "Externer Eintrag " + slug, null);
    }

    @Tool(name = "deploy_demo", description = """
            Version per GitHub Actions ausrollen. Ohne sha die neueste Version mit grünem Gate. Öffentliche Demos
            bekommen nur grüne Versionen. Fehlen Secrets, wird abgebrochen.""")
    public Object deploy(@ToolParam(description = "Slug") String slug,
                         @ToolParam(description = "Commit-SHA, leer = neueste grüne Version", required = false) String sha) {
        return guard.write("deploy_demo", args("slug", slug, "sha", sha), "Ausrollen " + slug, null);
    }

    @Tool(name = "start_demo", description = "Demo hochfahren (Container starten).")
    public Object start(@ToolParam(description = "Slug") String slug) {
        return guard.write("start_demo", args("slug", slug), "Starten " + slug, null);
    }

    @Tool(name = "stop_demo", description = "Demo herunterfahren (Container stoppen, Daten bleiben).")
    public Object stop(@ToolParam(description = "Slug") String slug) {
        return guard.write("stop_demo", args("slug", slug), "Stoppen " + slug, null);
    }

    @Tool(name = "restart_demo", description = "Demo neu starten.")
    public Object restart(@ToolParam(description = "Slug") String slug) {
        return guard.write("restart_demo", args("slug", slug), "Neustart " + slug, null);
    }

    @Tool(name = "reset_demo", description = "Demo-Daten auf den Ausgangszustand zurücksetzen (reset-Befehl aus demo.yaml).")
    public Object reset(@ToolParam(description = "Slug") String slug) {
        return guard.write("reset_demo", args("slug", slug), "Zurücksetzen " + slug, null);
    }

    @Tool(name = "update_catalog_entry", description = """
            Katalog-Reihenfolge und „hervorgehoben“ setzen. Texte und Tags verwalteter Demos stehen in demo.yaml
            (dort ändern, dann register_demo).""")
    public Object catalog(@ToolParam(description = "Slug") String slug,
                          @ToolParam(description = "Reihenfolge (kleiner = weiter vorn)", required = false) Integer sortOrder,
                          @ToolParam(description = "Hervorgehoben", required = false) Boolean featured) {
        return guard.write("update_catalog", args("slug", slug, "sortOrder", sortOrder, "featured", featured),
                "Katalog " + slug, null);
    }

    @Tool(name = "set_demo_lifecycle", description = """
            Lebenszyklus: permanent (läuft immer), on-demand (schläft nach idleMinutes, Besucher wecken sie),
            ttl (läuft ttlDays, dann abgelaufen). wakePolicy: anyone, share_link, admin.""")
    public Object lifecycle(@ToolParam(description = "Slug") String slug,
                            @ToolParam(description = "permanent, on-demand oder ttl", required = false) String lifecycle,
                            @ToolParam(description = "Tage bei ttl", required = false) Integer ttlDays,
                            @ToolParam(description = "Minuten bis zum Schlafen (on-demand)", required = false) Integer idleMinutes,
                            @ToolParam(description = "anyone, share_link oder admin", required = false) String wakePolicy,
                            @ToolParam(description = "Grüne Builds automatisch ausrollen", required = false) Boolean autoDeploy) {
        return guard.write("set_lifecycle", args("slug", slug, "lifecycle", lifecycle, "ttlDays", ttlDays,
                "idleMinutes", idleMinutes, "wakePolicy", wakePolicy, "autoDeploy", autoDeploy), "Lebenszyklus " + slug, null);
    }

    @Tool(name = "extend_ttl", description = "TTL einer Demo verlängern (ab jetzt), reaktiviert abgelaufene Demos.")
    public Object extend(@ToolParam(description = "Slug") String slug, @ToolParam(description = "Tage 1–365") int days) {
        return guard.write("extend_ttl", args("slug", slug, "days", days), "TTL " + slug + " +" + days + " Tage", null);
    }

    @Tool(name = "create_share_link", description = """
            Persönlichen Link erzeugen (z. B. für einen Lead): öffnet ungelistete Demos und weckt schlafende.
            Das Token steht nur in dieser Antwort.""")
    public Object shareLink(@ToolParam(description = "Slug") String slug,
                            @ToolParam(description = "Bezeichnung, z. B. Firma/Person", required = false) String label,
                            @ToolParam(description = "Lead-Referenz im CRM", required = false) String leadRef,
                            @ToolParam(description = "Gültig in Tagen, leer = unbegrenzt", required = false) Integer validDays) {
        return guard.write("create_share_link", args("slug", slug, "label", label, "leadRef", leadRef,
                "validDays", validDays), "Share-Link " + slug, null);
    }

    @Tool(name = "set_visibility", description = """
            Sichtbarkeit: public (Galerie + coach-raffael.com), unlisted (nur per Link), private. Öffentlich nur, wenn
            die ausgerollte Version Build- und Erlebnis-Gate bestanden hat. Standard: Freigabe durch Raffael.""")
    public Object visibility(@ToolParam(description = "Slug") String slug,
                             @ToolParam(description = "public, unlisted oder private") String visibility,
                             @ToolParam(description = "Idempotenzschlüssel", required = false) String idempotencyKey) {
        return guard.write("set_visibility", args("slug", slug, "visibility", visibility),
                slug + " → " + visibility, idempotencyKey);
    }

    @Tool(name = "change_routing", description = "Routing umstellen: path (demos…/slug) oder subdomain (slug.demos…). Wirksam ab dem nächsten Deploy.")
    public Object routing(@ToolParam(description = "Slug") String slug,
                          @ToolParam(description = "path oder subdomain") String routing) {
        return guard.write("change_routing", args("slug", slug, "routing", routing), slug + " → " + routing, null);
    }

    // ---------- Hilfen ----------

    private Map<String, Object> brief(Demo d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", d.slug());
        m.put("kind", d.kind());
        m.put("title", d.title().get("en"));
        m.put("state", d.state());
        m.put("visibility", d.visibility());
        m.put("lifecycle", d.lifecycle());
        m.put("url", d.url());
        if (d.managed()) {
            m.put("repo", d.sourceRepo());
            demos.currentVersion(d).ifPresent(v -> {
                m.put("sha", v.gitSha());
                m.put("publishable", v.publishable());
            });
        }
        return m;
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
