package com.coachraffael.demos.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Liest {@code demo.yaml} und prüft den Demo-Vertrag. Sammelt alle Probleme statt beim ersten abzubrechen,
 * damit Claude bzw. Raffael ein Manifest in einem Durchgang korrigieren können.
 *
 * <p>Sicherheitsrelevant: Nur eigene Images aus {@code ghcr.io/coach-raffael/} und eine kurze Liste offizieller
 * Basis-Images mit festem Tag sind erlaubt; Volumes sind benannte Volumes je Demo (keine Host-Pfade), Speicher
 * ist begrenzt. Der demo-agent führt nur aus, was hieraus erzeugt wurde.
 */
@Component
public class ManifestParser {

    public static final String OWN_IMAGE_PREFIX = "ghcr.io/coach-raffael/";

    static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");
    static final Set<String> RESERVED = Set.of("api", "admin", "auth", "_next", "static", "assets", "health",
            "catalog", "demos", "well-known", "public", "mcp");
    private static final Pattern SERVICE_NAME = Pattern.compile("^[a-z][a-z0-9-]{0,30}$");
    private static final Pattern OWN_IMAGE = Pattern.compile("^ghcr\\.io/coach-raffael/[a-z0-9._-]+(/[a-z0-9._-]+)*$");
    private static final Pattern BASE_IMAGE = Pattern.compile("^([a-z0-9-]+(/[a-z0-9-]+)?):([A-Za-z0-9._-]+)$");
    /** Offizielle Basis-Images, die neben eigenen Images laufen dürfen (Datenbanken, Caches, Broker). */
    static final Set<String> BASE_IMAGES = Set.of("postgres", "redis", "valkey/valkey", "mariadb", "mongo",
            "rabbitmq", "minio/minio", "axllent/mailpit");
    private static final Pattern PATH = Pattern.compile("^/[a-z0-9/_.-]*$");
    private static final Pattern MEMORY = Pattern.compile("^(\\d{1,5})([mg])$");
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern TAG = Pattern.compile("^[a-z0-9][a-z0-9+#.-]{0,30}$");
    private static final Set<String> TOP_LEVEL = Set.of("slug", "title", "teaser", "description", "category", "tags",
            "visibility", "lifecycle", "ttl_days", "idle_minutes", "routing", "wake", "services", "reset",
            "env_required", "quality");
    private static final Set<String> SERVICE_KEYS = Set.of("image", "port", "expose", "memory", "health", "env",
            "volumes", "depends_on");
    /** Variablen, die der Hub selbst setzt – ein Manifest darf sie nicht überschreiben. */
    static final Set<String> HUB_ENV = Set.of("BASE_PATH", "DEMO_SLUG", "DEMO_PUBLIC_URL", "DEMO_COOKIE_PREFIX");
    static final int MAX_SERVICE_MB = 2048;
    static final int MAX_TOTAL_MB = 3072;

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public DemoManifest parse(String text) {
        JsonNode root;
        try {
            root = yaml.readTree(text == null ? "" : text);
        } catch (Exception e) {
            throw new ManifestException(List.of("kein gültiges YAML: " + firstLine(e.getMessage())));
        }
        if (root == null || !root.isObject()) {
            throw new ManifestException(List.of("demo.yaml muss ein Objekt sein"));
        }
        List<String> p = new ArrayList<>();
        unknownKeys(root, TOP_LEVEL, "", p);

        String slug = text(root, "slug");
        if (slug == null || !SLUG.matcher(slug).matches()) {
            p.add("slug: Kleinbuchstaben, Ziffern, Bindestrich, 1–40 Zeichen (z. B. realestate-platform)");
        } else if (RESERVED.contains(slug)) {
            p.add("slug: \"" + slug + "\" ist vom Hub belegt");
        }

        Map<String, String> title = langMap(root.get("title"), "title", p);
        if (title.get("en") == null) {
            p.add("title.en fehlt (eigene Inhalte sind englisch)");
        }
        Map<String, String> teaser = langMap(root.get("teaser"), "teaser", p);
        Map<String, String> description = langMap(root.get("description"), "description", p);

        String category = oneOf(root, "category", "showcase", Set.of("showcase", "demo", "tool"), p);
        List<String> tags = new ArrayList<>();
        for (String t : stringList(root.get("tags"), "tags", p)) {
            if (!TAG.matcher(t).matches()) p.add("tags: \"" + t + "\" – Kleinbuchstaben, Ziffern, Bindestrich");
            else tags.add(t);
        }
        if (tags.size() > 12) p.add("tags: höchstens 12");

        String visibility = oneOf(root, "visibility", "private", Set.of("public", "unlisted", "private"), p);
        String lifecycle = oneOf(root, "lifecycle", "on-demand", Set.of("permanent", "on-demand", "ttl"), p);
        Integer ttlDays = intOrNull(root, "ttl_days", 1, 365, p);
        if ("ttl".equals(lifecycle) && ttlDays == null) p.add("ttl_days: Pflicht bei lifecycle: ttl");
        Integer idle = intOrNull(root, "idle_minutes", 5, 1440, p);
        String routing = oneOf(root, "routing", "path", Set.of("path", "subdomain"), p);
        String wake = oneOf(root, "wake", "anyone", Set.of("anyone", "share_link", "admin"), p);

        Map<String, DemoManifest.Service> services = services(root.get("services"), p);

        DemoManifest.Reset reset = null;
        JsonNode r = root.get("reset");
        if (r != null && !r.isNull()) {
            unknownKeys(r, Set.of("service", "command", "nightly"), "reset.", p);
            String svc = text(r, "service");
            List<String> cmd = stringList(r.get("command"), "reset.command", p);
            if (svc == null || !services.containsKey(svc)) p.add("reset.service: muss ein Dienst aus services sein");
            if (cmd.isEmpty()) p.add("reset.command: Liste mit mindestens einem Element");
            reset = new DemoManifest.Reset(svc, cmd, r.path("nightly").asBoolean(false));
        }

        List<String> envRequired = new ArrayList<>();
        for (String name : stringList(root.get("env_required"), "env_required", p)) {
            if (!ENV_NAME.matcher(name).matches()) p.add("env_required: \"" + name + "\" – GROSSBUCHSTABEN_MIT_UNTERSTRICH");
            else if (HUB_ENV.contains(name)) p.add("env_required: \"" + name + "\" setzt der Hub selbst");
            else envRequired.add(name);
        }
        for (var e : services.entrySet()) {
            for (String k : e.getValue().env().keySet()) {
                if (envRequired.contains(k)) p.add("services." + e.getKey() + ".env." + k + ": steht schon in env_required (Secret)");
            }
        }

        DemoManifest.Quality quality = quality(root.get("quality"), p);

        if (!p.isEmpty()) {
            throw new ManifestException(p);
        }
        return new DemoManifest(slug, title, teaser, description, category, tags, visibility, lifecycle, ttlDays,
                idle == null ? 60 : idle, routing, wake, services, reset, envRequired, quality);
    }

    private Map<String, DemoManifest.Service> services(JsonNode node, List<String> p) {
        Map<String, DemoManifest.Service> out = new LinkedHashMap<>();
        if (node == null || !node.isObject() || node.isEmpty()) {
            p.add("services: mindestens ein Dienst");
            return out;
        }
        if (node.size() > 8) p.add("services: höchstens 8 Dienste");
        int totalMb = 0;
        int roots = 0;
        Set<String> exposes = new java.util.HashSet<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            var e = it.next();
            String name = e.getKey();
            String at = "services." + name;
            JsonNode s = e.getValue();
            if (!SERVICE_NAME.matcher(name).matches()) p.add(at + ": Name aus Kleinbuchstaben, Ziffern, Bindestrich");
            if (!s.isObject()) {
                p.add(at + ": muss ein Objekt sein");
                continue;
            }
            unknownKeys(s, SERVICE_KEYS, at + ".", p);

            String image = text(s, "image");
            if (image == null) {
                p.add(at + ".image fehlt");
            } else if (image.startsWith(OWN_IMAGE_PREFIX)) {
                if (!OWN_IMAGE.matcher(image).matches()) {
                    p.add(at + ".image: eigene Images ohne Tag angeben – der Hub setzt die gebaute Version ein");
                }
            } else {
                var m = BASE_IMAGE.matcher(image);
                if (!m.matches() || !BASE_IMAGES.contains(m.group(1))) {
                    p.add(at + ".image: nur ghcr.io/coach-raffael/… oder freigegebene Basis-Images " + BASE_IMAGES
                            + " mit festem Tag");
                } else if ("latest".equals(m.group(3))) {
                    p.add(at + ".image: festen Tag statt latest angeben");
                }
            }

            Integer port = intOrNull(s, "port", 1, 65535, p, at + ".port");
            String expose = text(s, "expose");
            if (expose != null) {
                if (!PATH.matcher(expose).matches() || (expose.length() > 1 && expose.endsWith("/"))) {
                    p.add(at + ".expose: Pfad wie / oder /api (ohne / am Ende)");
                } else if (!exposes.add(expose)) {
                    p.add(at + ".expose: " + expose + " ist schon vergeben");
                }
                if ("/".equals(expose)) roots++;
                if (port == null) p.add(at + ".port: Pflicht, wenn expose gesetzt ist");
            }

            String memory = text(s, "memory");
            Integer mb = megabytes(memory);
            if (mb == null) {
                p.add(at + ".memory: Pflicht, z. B. 384m oder 1g");
            } else if (mb < 32 || mb > MAX_SERVICE_MB) {
                p.add(at + ".memory: zwischen 32m und " + MAX_SERVICE_MB + "m");
            } else {
                totalMb += mb;
            }

            String health = text(s, "health");
            if (health != null && !PATH.matcher(health).matches()) p.add(at + ".health: Pfad wie /api/health");
            if (health != null && expose == null) p.add(at + ".health: nur für Dienste mit expose");

            Map<String, String> env = new LinkedHashMap<>();
            JsonNode envNode = s.get("env");
            if (envNode != null && !envNode.isNull()) {
                if (!envNode.isObject()) {
                    p.add(at + ".env: Objekt NAME: wert");
                } else {
                    envNode.fields().forEachRemaining(v -> {
                        if (!ENV_NAME.matcher(v.getKey()).matches()) p.add(at + ".env." + v.getKey() + ": ungültiger Name");
                        else if (HUB_ENV.contains(v.getKey())) p.add(at + ".env." + v.getKey() + ": setzt der Hub selbst");
                        else if (!v.getValue().isValueNode()) p.add(at + ".env." + v.getKey() + ": nur einfache Werte");
                        else env.put(v.getKey(), v.getValue().asText());
                    });
                }
            }

            List<String> volumes = new ArrayList<>();
            for (String v : stringList(s.get("volumes"), at + ".volumes", p)) {
                if (!PATH.matcher(v).matches() || v.contains("..")) {
                    p.add(at + ".volumes: Pfad im Container wie /var/lib/postgresql/data (Host-Pfade sind nicht erlaubt)");
                } else {
                    volumes.add(v);
                }
            }
            List<String> dependsOn = stringList(s.get("depends_on"), at + ".depends_on", p);
            out.put(name, new DemoManifest.Service(image, port, expose, memory, health, env, volumes, dependsOn));
        }
        for (var e : out.entrySet()) {
            for (String d : e.getValue().dependsOn()) {
                if (!out.containsKey(d) || d.equals(e.getKey())) {
                    p.add("services." + e.getKey() + ".depends_on: \"" + d + "\" ist kein anderer Dienst");
                }
            }
        }
        if (roots != 1) p.add("services: genau ein Dienst braucht expose: / (Einstieg der Demo)");
        if (totalMb > MAX_TOTAL_MB) p.add("services: zusammen höchstens " + MAX_TOTAL_MB + "m Speicher (jetzt " + totalMb + "m)");
        if (out.values().stream().noneMatch(DemoManifest.Service::ownImage)) {
            p.add("services: mindestens ein eigenes Image aus " + OWN_IMAGE_PREFIX);
        }
        return out;
    }

    private DemoManifest.Quality quality(JsonNode node, List<String> p) {
        if (node == null || node.isNull()) {
            return new DemoManifest.Quality(List.of(), Map.of());
        }
        unknownKeys(node, Set.of("flows", "lighthouse"), "quality.", p);
        List<String> flows = new ArrayList<>();
        for (String f : stringList(node.get("flows"), "quality.flows", p)) {
            if (f.startsWith("/") || f.contains("..") || !f.matches("^[A-Za-z0-9_./-]+\\.(spec|test)\\.(ts|js|mjs)$")) {
                p.add("quality.flows: \"" + f + "\" – relativer Pfad zu einer Playwright-Datei (*.spec.ts)");
            } else {
                flows.add(f);
            }
        }
        Map<String, Integer> lighthouse = new LinkedHashMap<>();
        JsonNode lh = node.get("lighthouse");
        if (lh != null && !lh.isNull()) {
            unknownKeys(lh, Set.of("performance", "accessibility", "best-practices", "seo"), "quality.lighthouse.", p);
            lh.fields().forEachRemaining(e -> {
                if (!e.getValue().canConvertToInt() || e.getValue().asInt() < 0 || e.getValue().asInt() > 100) {
                    p.add("quality.lighthouse." + e.getKey() + ": Zahl 0–100");
                } else {
                    lighthouse.put(e.getKey(), e.getValue().asInt());
                }
            });
        }
        return new DemoManifest.Quality(flows, lighthouse);
    }

    // ---- kleine Helfer ----

    static Integer megabytes(String memory) {
        if (memory == null) return null;
        var m = MEMORY.matcher(memory);
        if (!m.matches()) return null;
        int n = Integer.parseInt(m.group(1));
        return "g".equals(m.group(2)) ? n * 1024 : n;
    }

    private static void unknownKeys(JsonNode node, Set<String> allowed, String prefix, List<String> p) {
        node.fieldNames().forEachRemaining(k -> {
            if (!allowed.contains(k)) p.add(prefix + k + ": unbekanntes Feld");
        });
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String oneOf(JsonNode node, String field, String fallback, Set<String> allowed, List<String> p) {
        String v = text(node, field);
        if (v == null) return fallback;
        if (!allowed.contains(v)) {
            p.add(field + ": eins von " + allowed);
            return fallback;
        }
        return v;
    }

    private static Integer intOrNull(JsonNode node, String field, int min, int max, List<String> p) {
        return intOrNull(node, field, min, max, p, field);
    }

    private static Integer intOrNull(JsonNode node, String field, int min, int max, List<String> p, String label) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (!v.canConvertToInt() || !v.isIntegralNumber() || v.asInt() < min || v.asInt() > max) {
            p.add(label + ": ganze Zahl " + min + "–" + max);
            return null;
        }
        return v.asInt();
    }

    private static List<String> stringList(JsonNode node, String label, List<String> p) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) return out;
        if (!node.isArray()) {
            p.add(label + ": Liste erwartet");
            return out;
        }
        for (JsonNode n : node) {
            if (!n.isValueNode() || n.asText().isBlank()) p.add(label + ": nur nicht-leere Texte");
            else out.add(n.asText().trim());
        }
        return out;
    }

    private static Map<String, String> langMap(JsonNode node, String label, List<String> p) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || node.isNull()) return out;
        if (node.isValueNode()) {
            out.put("en", node.asText().trim());
            return out;
        }
        if (!node.isObject()) {
            p.add(label + ": Text oder {en: …, de: …}");
            return out;
        }
        node.fields().forEachRemaining(e -> {
            if (!e.getKey().matches("^[a-z]{2}$")) p.add(label + "." + e.getKey() + ": Sprachkürzel wie en, de");
            else if (!e.getValue().isValueNode() || e.getValue().asText().isBlank()) p.add(label + "." + e.getKey() + ": Text");
            else if (e.getValue().asText().length() > 2000) p.add(label + "." + e.getKey() + ": höchstens 2000 Zeichen");
            else out.put(e.getKey(), e.getValue().asText().trim());
        });
        return out;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }
}
