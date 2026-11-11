package com.coachraffael.demos.deploy;

import com.coachraffael.demos.catalog.ScreenshotStore;
import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.HubProperties;
import com.coachraffael.demos.demo.Demo;
import com.coachraffael.demos.demo.DemoService;
import com.coachraffael.demos.demo.DemoVersion;
import com.coachraffael.demos.demo.VersionService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpunkte für GitHub Actions (Token {@code hubci_…}, Bereich {@code ci}).
 * <ul>
 *   <li>{@code POST /ci/builds} – build-showcase.yml meldet Images + Build-Gate</li>
 *   <li>{@code GET  /ci/deploy/{slug}/{version}/bundle} – deploy-demo.yml holt compose.yml, .env, agent.json</li>
 *   <li>{@code POST /ci/deploy/{slug}/{version}/result} – Ausrollen erfolgreich/fehlgeschlagen</li>
 *   <li>{@code POST /ci/deploy/{slug}/{version}/experience} – Erlebnis-Gate gegen die laufende Demo</li>
 *   <li>{@code POST /ci/deploy/{slug}/{version}/screenshot} – PNG für Galerie und Homepage</li>
 * </ul>
 */
@RestController
public class CiController {

    private static final Logger log = LoggerFactory.getLogger(CiController.class);

    private final DemoService demos;
    private final VersionService versions;
    private final DeployService deploy;
    private final ScreenshotStore screenshots;
    private final HubProperties props;

    public CiController(DemoService demos, VersionService versions, DeployService deploy, ScreenshotStore screenshots,
                        HubProperties props) {
        this.demos = demos;
        this.versions = versions;
        this.deploy = deploy;
        this.screenshots = screenshots;
        this.props = props;
    }

    public record BuildRequest(@NotBlank String repo, @NotBlank String slug, @NotBlank String sha, String path,
                               Map<String, String> images, JsonNode checks, String runUrl) {
    }

    /**
     * Ein Build ist nur für registrierte Demos möglich (Registrieren entscheidet Raffael bzw. Claude per MCP).
     * Vor dem Speichern wird demo.yaml im gebauten Commit neu gelesen – Manifest und Version passen so immer zusammen.
     */
    @PostMapping("/ci/builds")
    public Map<String, Object> build(@RequestBody BuildRequest r) {
        Actor ci = Actor.currentUi();
        Demo d = demos.get(r.slug());
        if (!d.managed() || !r.repo().equalsIgnoreCase(d.sourceRepo())) {
            throw new com.coachraffael.demos.common.Errors.Conflict("Repo " + r.repo() + " ist für " + r.slug() + " nicht registriert");
        }
        demos.register(d.sourceRepo(), r.path() == null ? d.sourcePath() : r.path(), r.sha(), ci);
        DemoVersion v = versions.recordBuild(new VersionService.BuildReport(r.repo(), r.slug(), r.sha(), r.images(),
                r.checks(), r.runUrl()), ci);
        boolean deploying = false;
        String deployError = null;
        try {
            deploying = deploy.afterBuild(v, r.slug());
        } catch (RuntimeException e) {
            // Build ist gespeichert; Ausrollen kann aus Hub/MCP nachgeholt werden
            deployError = e.getMessage();
            log.warn("Automatisches Ausrollen von {} fehlgeschlagen: {}", r.slug(), e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versionId", v.id());
        out.put("gatePassed", v.gatePassed());
        out.put("deploying", deploying);
        if (deployError != null) out.put("deployError", deployError);
        out.put("hub", props.publicBase() + "/admin/" + r.slug());
        return out;
    }

    @GetMapping("/ci/deploy/{slug}/{versionId}/bundle")
    public DeployService.Bundle bundle(@PathVariable String slug, @PathVariable long versionId) {
        return deploy.bundle(slug, versionId);
    }

    public record ResultRequest(boolean ok, String detail, String runUrl) {
    }

    @PostMapping("/ci/deploy/{slug}/{versionId}/result")
    public Map<String, Object> result(@PathVariable String slug, @PathVariable long versionId, @RequestBody ResultRequest r) {
        Demo d = deploy.recordResult(slug, versionId, r.ok(), r.detail(), r.runUrl(), Actor.currentUi());
        return Map.of("state", d.state(), "url", d.url());
    }

    public record ExperienceRequest(JsonNode checks) {
    }

    @PostMapping("/ci/deploy/{slug}/{versionId}/experience")
    public Map<String, Object> experience(@PathVariable String slug, @PathVariable long versionId,
                                          @RequestBody ExperienceRequest r) {
        DemoVersion v = versions.recordExperience(slug, versionId, r.checks(), Actor.currentUi());
        return Map.of("experiencePassed", v.experiencePassed(), "publishable", v.publishable());
    }

    @PostMapping(value = "/ci/deploy/{slug}/{versionId}/screenshot", consumes = "image/png")
    public Map<String, Object> screenshot(@PathVariable String slug, @PathVariable long versionId,
                                          @RequestBody byte[] png) {
        Demo d = demos.get(slug);
        DemoVersion v = versions.get(versionId);
        if (v.demoId() != d.id()) {
            throw new com.coachraffael.demos.common.Errors.Conflict("Version gehört nicht zu " + slug);
        }
        screenshots.save(slug, png);
        String url = props.publicBase() + "/api/catalog/" + slug + "/screenshot.png?v=" + v.gitSha().substring(0, 7);
        demos.updateCatalog(slug, null, null, url, Actor.currentUi());
        return Map.of("screenshotUrl", url);
    }
}
