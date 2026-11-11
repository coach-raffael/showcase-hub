package com.coachraffael.demos.compose;

import com.coachraffael.demos.common.HubProperties;
import com.coachraffael.demos.manifest.DemoManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Erzeugt aus einem geprüften Manifest die {@code compose.yml} einer Demo – mit Traefik-Labels für den globalen
 * Traefik des Hostinger Docker Managers (Entrypoint {@code websecure}, Resolver {@code letsencrypt}).
 *
 * <p>Pfad-Routing: {@code demos.coach-raffael.com/<slug>…}; die App läuft selbst unter {@code BASE_PATH=/<slug>}
 * (kein StripPrefix – sonst stimmen Links und Assets nicht). Subdomain-Routing: {@code <slug>.demos.coach-raffael.com}.
 * Läuft eine Demo nicht, gibt es keinen Router für sie, und die Anfrage fällt auf die Schlafseite des Hubs
 * (Router mit niedrigerer Priorität).
 */
@Component
public class ComposeRenderer {

    /** Label, an dem der demo-agent die Container einer Demo erkennt. */
    public static final String DEMO_LABEL = "coach-raffael.demo";

    private final HubProperties props;
    private final ObjectMapper yaml;

    public ComposeRenderer(HubProperties props) {
        this.props = props;
        this.yaml = new ObjectMapper(new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS));
    }

    /**
     * @param images     je eigenem Dienst die konkrete Referenz der Version ({@code …@sha256:…} oder {@code …:<sha>})
     * @param visibility aktuelle Sichtbarkeit aus dem Hub (nicht öffentliche Demos bekommen {@code noindex})
     */
    public String render(DemoManifest m, Map<String, String> images, String visibility) {
        String slug = m.slug();
        boolean path = "path".equals(m.routing());
        String host = path ? props.demoDomain() : slug + "." + props.demoDomain();
        String basePath = path ? "/" + slug : "";
        String publicUrl = "https://" + host + basePath;

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", projectName(slug));
        Map<String, Object> services = new LinkedHashMap<>();
        Map<String, Object> volumes = new LinkedHashMap<>();
        String headers = "demo-" + slug + "-headers";

        for (var e : m.services().entrySet()) {
            String name = e.getKey();
            DemoManifest.Service s = e.getValue();
            Map<String, Object> svc = new LinkedHashMap<>();
            String image = s.ownImage() ? images.get(name) : s.image();
            if (image == null || (s.ownImage() && !image.startsWith(s.image()))) {
                throw new IllegalArgumentException("Für Dienst " + name + " fehlt das gebaute Image (" + s.image() + ")");
            }
            svc.put("image", image);
            svc.put("restart", "unless-stopped");
            svc.put("mem_limit", s.memory());
            svc.put("pids_limit", 512);
            svc.put("security_opt", List.of("no-new-privileges:true"));
            svc.put("logging", Map.of("driver", "json-file", "options", Map.of("max-size", "10m", "max-file", "3")));

            Map<String, String> env = new LinkedHashMap<>();
            env.put("BASE_PATH", basePath);
            env.put("DEMO_SLUG", slug);
            env.put("DEMO_PUBLIC_URL", publicUrl);
            env.put("DEMO_COOKIE_PREFIX", slug);
            // Feste Werte: $ maskieren, sonst interpoliert Compose sie
            s.env().forEach((k, v) -> env.put(k, v.replace("$", "$$")));
            // Secrets kommen aus der .env neben der compose.yml
            m.envRequired().forEach(k -> env.put(k, "${" + k + "}"));
            // Listenform KEY=wert: so bleibt jeder Wert ein String (YAML würde true/123 sonst umdeuten)
            svc.put("environment", env.entrySet().stream().map(x -> x.getKey() + "=" + x.getValue()).toList());

            if (!s.volumes().isEmpty()) {
                List<String> mounts = new ArrayList<>();
                for (int i = 0; i < s.volumes().size(); i++) {
                    String vol = name + "-data" + (i == 0 ? "" : "-" + (i + 1));
                    volumes.put(vol, Map.of());
                    mounts.add(vol + ":" + s.volumes().get(i));
                }
                svc.put("volumes", mounts);
            }
            if (!s.dependsOn().isEmpty()) {
                svc.put("depends_on", s.dependsOn());
            }
            svc.put("networks", List.of("internal"));

            Map<String, String> labels = new LinkedHashMap<>();
            labels.put(DEMO_LABEL, slug);
            if (s.expose() != null) {
                String router = "demo-" + slug + "-" + name;
                labels.put("traefik.enable", "true");
                labels.put("traefik.http.routers." + router + ".rule", rule(host, path ? basePath : "", s.expose()));
                labels.put("traefik.http.routers." + router + ".entrypoints", "websecure");
                labels.put("traefik.http.routers." + router + ".tls.certresolver", "letsencrypt");
                labels.put("traefik.http.routers." + router + ".priority", String.valueOf(priority(path ? basePath : "", s.expose())));
                labels.put("traefik.http.routers." + router + ".middlewares", headers);
                labels.put("traefik.http.routers." + router + ".service", router);
                labels.put("traefik.http.services." + router + ".loadbalancer.server.port", String.valueOf(s.port()));
            }
            svc.put("labels", labels);   // wird unten in die Listenform gebracht
            services.put(name, svc);
        }

        // Sicherheits-Header für alle Router der Demo (Teil des Qualitätsgates), am Einstiegsdienst definiert
        @SuppressWarnings("unchecked")
        Map<String, String> entryLabels = (Map<String, String>) ((Map<String, Object>) services.get(m.entryService())).get("labels");
        String mw = "traefik.http.middlewares." + headers + ".headers.";
        entryLabels.put(mw + "stsSeconds", "31536000");
        entryLabels.put(mw + "contentTypeNosniff", "true");
        entryLabels.put(mw + "referrerPolicy", "strict-origin-when-cross-origin");
        entryLabels.put(mw + "customResponseHeaders.X-Robots-Tag", "public".equals(visibility) ? "all" : "noindex, nofollow");

        for (Object o : services.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> svc = (Map<String, Object>) o;
            @SuppressWarnings("unchecked")
            Map<String, String> labels = (Map<String, String>) svc.get("labels");
            svc.put("labels", labels.entrySet().stream().map(x -> x.getKey() + "=" + x.getValue()).toList());
        }
        doc.put("services", services);
        doc.put("networks", Map.of("internal", Map.of()));
        if (!volumes.isEmpty()) {
            doc.put("volumes", volumes);
        }
        try {
            return "# Erzeugt vom Showcase-Hub aus demo.yaml – nicht von Hand ändern\n" + yaml.writeValueAsString(doc);
        } catch (Exception e) {
            throw new IllegalStateException("compose.yml nicht erzeugbar", e);
        }
    }

    /** Die Datei, aus der der demo-agent den Reset-Befehl liest (er nimmt nichts anderes von außen an). */
    public String agentConfig(DemoManifest m) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("slug", m.slug());
        cfg.put("project", projectName(m.slug()));
        if (m.reset() != null) {
            cfg.put("reset", Map.of("service", m.reset().service(), "command", m.reset().command()));
        }
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(cfg) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Öffentliche Adresse der Demo (Einstieg). */
    public String publicUrl(DemoManifest m) {
        return "path".equals(m.routing())
                ? "https://" + props.demoDomain() + "/" + m.slug()
                : "https://" + m.slug() + "." + props.demoDomain();
    }

    public static String projectName(String slug) {
        return "demo-" + slug;
    }

    static String rule(String host, String basePath, String expose) {
        String prefix = basePath + ("/".equals(expose) ? "" : expose);
        if (prefix.isEmpty()) {
            return "Host(`" + host + "`)";
        }
        return "Host(`" + host + "`) && (Path(`" + prefix + "`) || PathPrefix(`" + prefix + "/`))";
    }

    /** Längere Präfixe zuerst; alles über der Schlafseite des Hubs (Priorität 10). */
    static int priority(String basePath, String expose) {
        String prefix = basePath + ("/".equals(expose) ? "" : expose);
        return 100 + prefix.length();
    }
}
