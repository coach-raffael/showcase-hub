package com.coachraffael.demos.demo;

import com.coachraffael.demos.catalog.CatalogNotifier;
import com.coachraffael.demos.catalog.ScreenshotRequester;
import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.AuditLog;
import com.coachraffael.demos.common.Errors;
import com.coachraffael.demos.common.HubProperties;
import com.coachraffael.demos.github.GithubClient;
import com.coachraffael.demos.manifest.DemoManifest;
import com.coachraffael.demos.manifest.ManifestParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Registry: Demos registrieren (Repo nennen → demo.yaml lesen), Katalog pflegen, Sichtbarkeit und Lebenszyklus.
 *
 * <p>Regeln:
 * <ul>
 *   <li>Texte, Tags, Lebenszyklus kommen bei verwalteten Demos aus {@code demo.yaml} – eine Quelle, kein Abgleich.
 *       Im Hub gepflegt werden nur Reihenfolge, „hervorgehoben“ und Screenshot.</li>
 *   <li>{@code visibility} aus demo.yaml ist nur ein Wunsch. Sichtbarkeit ändert ausschließlich
 *       {@link #setVisibility}; öffentlich nur mit einer Version, deren Gate komplett grün ist.</li>
 *   <li>Routing wird beim ersten Registrieren festgelegt; ein Wechsel geht nur über {@link #changeRouting}.</li>
 * </ul>
 */
@Service
public class DemoService {

    static final String COLUMNS = """
            id, slug, kind, source_repo, source_path, external_url, title::text as title, teaser::text as teaser,
            description::text as description, category, tags, visibility, sort_order, featured, screenshot_url,
            lifecycle, ttl_days, expires_at, routing, idle_minutes, wake_policy, auto_deploy, state, state_at,
            last_woken_at, current_version_id, manifest_yaml, created_at, updated_at""";
    private static final Pattern URL = Pattern.compile("^https://[A-Za-z0-9.-]+(:\\d+)?(/[^\\s]*)?$");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ManifestParser parser;
    private final GithubClient github;
    private final AuditLog audit;
    private final HubProperties props;
    private final CatalogNotifier notifier;

    private final ScreenshotRequester screenshots;

    public DemoService(JdbcClient jdbc, ObjectMapper json, ManifestParser parser, GithubClient github,
                       AuditLog audit, HubProperties props, CatalogNotifier notifier, ScreenshotRequester screenshots) {
        this.jdbc = jdbc;
        this.json = json;
        this.parser = parser;
        this.github = github;
        this.audit = audit;
        this.props = props;
        this.notifier = notifier;
        this.screenshots = screenshots;
    }

    public record RegisterResult(Demo demo, boolean created, List<String> notes) {
    }

    // ---------- Lesen ----------

    @Transactional(readOnly = true)
    public List<Demo> list() {
        return jdbc.sql("select " + COLUMNS + " from demos order by sort_order, slug").query(mapper()).list();
    }

    @Transactional(readOnly = true)
    public Demo get(String slug) {
        return find(slug).orElseThrow(() -> Errors.notFound("Demo", slug));
    }

    @Transactional(readOnly = true)
    public Optional<Demo> find(String slug) {
        return jdbc.sql("select " + COLUMNS + " from demos where slug = :s").param("s", slug).query(mapper()).optional();
    }

    /** Das zuletzt eingelesene, geprüfte Manifest einer verwalteten Demo. */
    @Transactional(readOnly = true)
    public DemoManifest manifest(String slug) {
        String yaml = jdbc.sql("select manifest_yaml from demos where slug = :s and kind = 'managed'")
                .param("s", slug).query(String.class).optional()
                .orElseThrow(() -> Errors.notFound("Verwaltete Demo", slug));
        return parser.parse(yaml);
    }

    // ---------- Registrieren ----------

    /**
     * Liest {@code demo.yaml} aus dem Repo und legt die Demo an bzw. aktualisiert sie. Gleicher Slug aus einem
     * anderen Repo ist ein Konflikt – ein Repo kann keine fremde Demo übernehmen.
     */
    @Transactional
    public RegisterResult register(String repo, String path, String ref, Actor actor) {
        String file = path == null || path.isBlank() ? "demo.yaml" : path.trim();
        String yaml = github.fetchFile(repo, file, ref == null || ref.isBlank() ? "main" : ref.trim());
        DemoManifest m = parser.parse(yaml);
        return upsert(repo, file, yaml, m, actor);
    }

    @Transactional
    public RegisterResult upsert(String repo, String file, String yaml, DemoManifest m, Actor actor) {
        List<String> notes = new ArrayList<>();
        Optional<Demo> existing = find(m.slug());
        String manifestJson = write(m);
        if (existing.isEmpty()) {
            jdbc.sql("""
                            insert into demos (slug, kind, source_repo, source_path, manifest_yaml, manifest, title, teaser,
                                               description, category, tags, visibility, lifecycle, ttl_days, routing,
                                               idle_minutes, wake_policy)
                            values (:slug, 'managed', :repo, :path, :yaml, cast(:m as jsonb), cast(:title as jsonb),
                                    cast(:teaser as jsonb), cast(:desc as jsonb), :cat, :tags, 'private', :lc, :ttl,
                                    :routing, :idle, :wake)""")
                    .param("slug", m.slug()).param("repo", repo).param("path", file).param("yaml", yaml)
                    .param("m", manifestJson).param("title", write(m.title())).param("teaser", write(m.teaser()))
                    .param("desc", write(m.description())).param("cat", m.category())
                    .param("tags", m.tags().toArray(String[]::new)).param("lc", m.lifecycle()).param("ttl", m.ttlDays())
                    .param("routing", m.routing()).param("idle", m.idleMinutes()).param("wake", m.wake())
                    .update();
            notes.add("Angelegt als privat. Nach dem ersten grünen Build wird automatisch ausgerollt.");
            if (!"private".equals(m.visibility())) {
                notes.add("demo.yaml wünscht visibility: " + m.visibility()
                        + " – freischalten mit set_visibility, sobald das Gate komplett grün ist.");
            }
            Demo d = get(m.slug());
            audit.record(actor, "demo.register", "demo", d.id(), null, Map.of("repo", repo, "slug", m.slug()));
            return new RegisterResult(d, true, notes);
        }

        Demo before = existing.get();
        if (!before.managed() || !repo.equalsIgnoreCase(before.sourceRepo())) {
            throw new Errors.Conflict("Slug " + m.slug() + " gehört schon zu "
                    + (before.managed() ? before.sourceRepo() : "einem externen Eintrag"));
        }
        if (!m.routing().equals(before.routing())) {
            notes.add("routing in demo.yaml (" + m.routing() + ") weicht ab – bleibt " + before.routing()
                    + "; Umstellung nur über change_routing.");
        }
        if (!m.visibility().equals(before.visibility())) {
            notes.add("demo.yaml wünscht visibility: " + m.visibility() + ", aktuell " + before.visibility()
                    + " – ändern mit set_visibility.");
        }
        jdbc.sql("""
                        update demos set source_path = :path, manifest_yaml = :yaml, manifest = cast(:m as jsonb),
                               title = cast(:title as jsonb), teaser = cast(:teaser as jsonb),
                               description = cast(:desc as jsonb), category = :cat, tags = :tags, lifecycle = :lc,
                               ttl_days = :ttl, idle_minutes = :idle, wake_policy = :wake, updated_at = now()
                        where id = :id""")
                .param("path", file).param("yaml", yaml).param("m", manifestJson)
                .param("title", write(m.title())).param("teaser", write(m.teaser())).param("desc", write(m.description()))
                .param("cat", m.category()).param("tags", m.tags().toArray(String[]::new)).param("lc", m.lifecycle())
                .param("ttl", m.ttlDays()).param("idle", m.idleMinutes()).param("wake", m.wake()).param("id", before.id())
                .update();
        Demo after = get(m.slug());
        audit.record(actor, "demo.reread", "demo", after.id(), summary(before), summary(after));
        notes.add("demo.yaml neu eingelesen. Wirksam für Container ab dem nächsten Deploy.");
        if ("public".equals(after.visibility())) {
            notifier.catalogChanged("updated:" + after.slug());
        }
        return new RegisterResult(after, false, notes);
    }

    /** Externer Katalogeintrag (z. B. langtutor.cloud) – nur Link und Texte, nichts zu steuern. */
    @Transactional
    public Demo upsertExternal(String slug, String url, Map<String, String> title, Map<String, String> teaser,
                               String category, List<String> tags, Actor actor) {
        if (url == null || !URL.matcher(url).matches()) {
            throw new IllegalArgumentException("url: https-Adresse erwartet");
        }
        if (title == null || title.get("en") == null || title.get("en").isBlank()) {
            throw new IllegalArgumentException("title.en fehlt");
        }
        String cat = category == null ? "tool" : category;
        if (!Set.of("showcase", "demo", "tool").contains(cat)) {
            throw new IllegalArgumentException("category: showcase, demo oder tool");
        }
        Optional<Demo> existing = find(slug);
        if (existing.isPresent() && existing.get().managed()) {
            throw new Errors.Conflict("Slug " + slug + " ist eine verwaltete Demo");
        }
        jdbc.sql("""
                        insert into demos (slug, kind, external_url, title, teaser, category, tags, state)
                        values (:slug, 'external', :url, cast(:title as jsonb), cast(:teaser as jsonb), :cat, :tags, 'running')
                        on conflict (slug) do update set external_url = excluded.external_url, title = excluded.title,
                            teaser = excluded.teaser, category = excluded.category, tags = excluded.tags, updated_at = now()""")
                .param("slug", slug).param("url", url).param("title", write(title))
                .param("teaser", write(teaser == null ? Map.of() : teaser)).param("cat", cat)
                .param("tags", (tags == null ? List.<String>of() : tags).toArray(String[]::new)).update();
        Demo d = get(slug);
        audit.record(actor, existing.isPresent() ? "external.update" : "external.create", "demo", d.id(),
                existing.map(DemoService::summary).orElse(null), summary(d));
        // Neuer Eintrag oder neue Adresse → Playwright fotografiert die Seite (screenshot-externals.yml)
        if (existing.isEmpty() || !url.equals(existing.get().externalUrl())) {
            screenshots.request(slug);
        }
        if ("public".equals(d.visibility())) {
            notifier.catalogChanged("updated:" + slug);
        }
        return d;
    }

    // ---------- Katalog, Sichtbarkeit, Lebenszyklus ----------

    /** Was der Hub selbst pflegt: Reihenfolge, hervorgehoben, Screenshot. */
    @Transactional
    public Demo updateCatalog(String slug, Integer sortOrder, Boolean featured, String screenshotUrl, Actor actor) {
        Demo before = get(slug);
        if (screenshotUrl != null && !screenshotUrl.isBlank() && !URL.matcher(screenshotUrl).matches()) {
            throw new IllegalArgumentException("screenshotUrl: https-Adresse erwartet");
        }
        jdbc.sql("""
                        update demos set sort_order = coalesce(:o, sort_order), featured = coalesce(:f, featured),
                               screenshot_url = case when cast(:shot as text) is null then screenshot_url
                                                     when cast(:shot as text) = '' then null
                                                     else cast(:shot as text) end,
                               updated_at = now()
                        where id = :id""")
                .param("o", sortOrder).param("f", featured).param("shot", screenshotUrl).param("id", before.id()).update();
        Demo after = get(slug);
        audit.record(actor, "catalog.update", "demo", after.id(), summary(before), summary(after));
        if ("public".equals(after.visibility())) {
            notifier.catalogChanged("updated:" + slug);
        }
        return after;
    }

    /**
     * Sichtbarkeit setzen. Öffentlich wird eine verwaltete Demo nur, wenn ihre aktuelle Version das Gate komplett
     * bestanden hat (Build und Erlebnis) – „flawless“ ist eine Bedingung, kein Vorsatz.
     */
    @Transactional
    public Demo setVisibility(String slug, String visibility, Actor actor) {
        if (!Set.of("public", "unlisted", "private").contains(visibility)) {
            throw new IllegalArgumentException("visibility: public, unlisted oder private");
        }
        Demo before = get(slug);
        if ("public".equals(visibility) && before.managed()) {
            DemoVersion v = currentVersion(before)
                    .orElseThrow(() -> new Errors.Conflict("Noch keine ausgerollte Version – öffentlich erst mit grünem Gate"));
            if (!v.gatePassed()) {
                throw new Errors.Conflict("Build-Gate der Version " + v.gitSha() + " ist rot – nicht öffentlich schaltbar");
            }
            if (!Boolean.TRUE.equals(v.experiencePassed())) {
                throw new Errors.Conflict(v.experiencePassed() == null
                        ? "Erlebnis-Prüfung (Playwright/Lighthouse) für " + v.gitSha() + " steht noch aus"
                        : "Erlebnis-Prüfung für " + v.gitSha() + " ist rot – nicht öffentlich schaltbar");
            }
        }
        jdbc.sql("update demos set visibility = :v, updated_at = now() where id = :id")
                .param("v", visibility).param("id", before.id()).update();
        Demo after = get(slug);
        audit.record(actor, "demo.visibility", "demo", after.id(), Map.of("visibility", before.visibility()),
                Map.of("visibility", visibility));
        if ("public".equals(visibility) || "public".equals(before.visibility())) {
            notifier.catalogChanged("visibility:" + slug);
        }
        return after;
    }

    @Transactional
    public Demo setLifecycle(String slug, String lifecycle, Integer ttlDays, Integer idleMinutes, String wakePolicy,
                             Boolean autoDeploy, Actor actor) {
        Demo before = requireManaged(slug);
        String lc = lifecycle == null ? before.lifecycle() : lifecycle;
        if (!Set.of("permanent", "on-demand", "ttl").contains(lc)) {
            throw new IllegalArgumentException("lifecycle: permanent, on-demand oder ttl");
        }
        Integer ttl = ttlDays == null ? before.ttlDays() : ttlDays;
        if ("ttl".equals(lc) && ttl == null) {
            throw new IllegalArgumentException("ttlDays: Pflicht bei lifecycle ttl");
        }
        if (wakePolicy != null && !Set.of("anyone", "share_link", "admin").contains(wakePolicy)) {
            throw new IllegalArgumentException("wakePolicy: anyone, share_link oder admin");
        }
        jdbc.sql("""
                        update demos set lifecycle = :lc, ttl_days = :ttl, idle_minutes = coalesce(:idle, idle_minutes),
                               wake_policy = coalesce(:wake, wake_policy), auto_deploy = coalesce(:auto, auto_deploy),
                               expires_at = case when :lc = 'ttl' then coalesce(expires_at, now() + make_interval(days => :ttl))
                                                 else null end,
                               updated_at = now()
                        where id = :id""")
                .param("lc", lc).param("ttl", ttl).param("idle", idleMinutes).param("wake", wakePolicy)
                .param("auto", autoDeploy).param("id", before.id()).update();
        Demo after = get(slug);
        audit.record(actor, "demo.lifecycle", "demo", after.id(), summary(before), summary(after));
        return after;
    }

    /** TTL verlängern: Ablauf neu ab jetzt. */
    @Transactional
    public Demo extendTtl(String slug, int days, Actor actor) {
        Demo d = requireManaged(slug);
        if (days < 1 || days > 365) throw new IllegalArgumentException("days: 1–365");
        jdbc.sql("update demos set lifecycle = 'ttl', ttl_days = :d, expires_at = now() + make_interval(days => :d), updated_at = now() where id = :id")
                .param("d", days).param("id", d.id()).update();
        Demo after = get(slug);
        audit.record(actor, "demo.ttl", "demo", d.id(), summary(d), summary(after));
        return after;
    }

    @Transactional
    public Demo changeRouting(String slug, String routing, Actor actor) {
        if (!Set.of("path", "subdomain").contains(routing)) {
            throw new IllegalArgumentException("routing: path oder subdomain");
        }
        Demo before = requireManaged(slug);
        jdbc.sql("update demos set routing = :r, updated_at = now() where id = :id")
                .param("r", routing).param("id", before.id()).update();
        Demo after = get(slug);
        audit.record(actor, "demo.routing", "demo", after.id(), Map.of("routing", before.routing()),
                Map.of("routing", routing, "hinweis", "wirksam ab dem nächsten Deploy; App braucht passenden BASE_PATH"));
        if ("public".equals(after.visibility())) {
            notifier.catalogChanged("routing:" + slug);
        }
        return after;
    }

    /**
     * Archivieren statt löschen: Versionen sind Nachweise und bleiben. Die Demo wird privat und gilt als abgelaufen;
     * stoppen muss der Aufrufer über den Lebenszyklus.
     */
    @Transactional
    public Demo archive(String slug, Actor actor) {
        if (!"ui".equals(actor.channel())) {
            throw new Errors.Conflict("Archivieren geht nur in der Oberfläche");
        }
        Demo before = get(slug);
        jdbc.sql("update demos set visibility = 'private', state = 'expired', state_at = now(), updated_at = now() where id = :id")
                .param("id", before.id()).update();
        Demo after = get(slug);
        audit.record(actor, "demo.archive", "demo", after.id(), summary(before), summary(after));
        if ("public".equals(before.visibility())) {
            notifier.catalogChanged("archived:" + slug);
        }
        return after;
    }

    // ---------- Zustand (vom Lebenszyklus genutzt) ----------

    @Transactional
    public void setState(long id, String state) {
        jdbc.sql("""
                        update demos set state = :s, state_at = case when state = :s then state_at else now() end
                        where id = :id""")
                .param("s", state).param("id", id).update();
    }

    @Transactional(readOnly = true)
    public Optional<DemoVersion> currentVersion(Demo d) {
        if (d.currentVersionId() == null) return Optional.empty();
        return jdbc.sql(VersionService.SELECT + " where id = :id").param("id", d.currentVersionId())
                .query(DemoVersion.class).optional();
    }

    Demo requireManaged(String slug) {
        Demo d = get(slug);
        if (!d.managed()) {
            throw new Errors.Conflict(slug + " ist ein externer Katalogeintrag – nichts zu steuern");
        }
        return d;
    }

    // ---------- Hilfen ----------

    public String publicUrl(String slug, String routing) {
        return "path".equals(routing)
                ? "https://" + props.demoDomain() + "/" + slug
                : "https://" + slug + "." + props.demoDomain();
    }

    static Map<String, Object> summary(Demo d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", d.slug());
        m.put("visibility", d.visibility());
        m.put("lifecycle", d.lifecycle());
        m.put("ttlDays", d.ttlDays());
        m.put("routing", d.routing());
        m.put("wakePolicy", d.wakePolicy());
        m.put("sortOrder", d.sortOrder());
        m.put("featured", d.featured());
        m.put("title", d.title());
        return m;
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    RowMapper<Demo> mapper() {
        TypeReference<Map<String, String>> mapType = new TypeReference<>() {
        };
        return (ResultSet rs, int n) -> {
            String kind = rs.getString("kind");
            String slug = rs.getString("slug");
            String routing = rs.getString("routing");
            try {
                return new Demo(
                        rs.getLong("id"), slug, kind, rs.getString("source_repo"), rs.getString("source_path"),
                        rs.getString("external_url"),
                        json.readValue(rs.getString("title"), mapType),
                        json.readValue(rs.getString("teaser"), mapType),
                        json.readValue(rs.getString("description"), mapType),
                        rs.getString("category"), strings(rs.getArray("tags")), rs.getString("visibility"),
                        rs.getInt("sort_order"), rs.getBoolean("featured"), rs.getString("screenshot_url"),
                        rs.getString("lifecycle"), (Integer) rs.getObject("ttl_days"),
                        rs.getObject("expires_at", OffsetDateTime.class), routing, rs.getInt("idle_minutes"),
                        rs.getString("wake_policy"), rs.getBoolean("auto_deploy"), rs.getString("state"),
                        rs.getObject("state_at", OffsetDateTime.class), rs.getObject("last_woken_at", OffsetDateTime.class),
                        (Long) rs.getObject("current_version_id"),
                        "managed".equals(kind) ? publicUrl(slug, routing) : rs.getString("external_url"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new SQLException("JSON in demos nicht lesbar", e);
            }
        };
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) return List.of();
        return Arrays.asList((String[]) a.getArray());
    }
}
