package com.coachraffael.demos.demo;

import com.coachraffael.demos.agent.AgentClient;
import com.coachraffael.demos.agent.LifecycleService;
import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.deploy.DeployService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verwaltung für die Oberfläche (Rolle demos-admin). Dieselben Services nutzt der MCP-Server. */
@RestController
public class DemoController {

    private final DemoService demos;
    private final VersionService versions;
    private final DemoEvents events;
    private final SecretService secrets;
    private final ShareLinkService shareLinks;
    private final DeployService deploy;
    private final LifecycleService lifecycle;
    private final JdbcClient jdbc;

    public DemoController(DemoService demos, VersionService versions, DemoEvents events, SecretService secrets,
                          ShareLinkService shareLinks, DeployService deploy, LifecycleService lifecycle, JdbcClient jdbc) {
        this.demos = demos;
        this.versions = versions;
        this.events = events;
        this.secrets = secrets;
        this.shareLinks = shareLinks;
        this.deploy = deploy;
        this.lifecycle = lifecycle;
        this.jdbc = jdbc;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Overview(Demo demo, DemoVersion currentVersion) {
    }

    @GetMapping("/demos")
    public List<Overview> list() {
        return demos.list().stream().map(d -> new Overview(d, demos.currentVersion(d).orElse(null))).toList();
    }

    @GetMapping("/demos/{slug}")
    public Map<String, Object> detail(@PathVariable String slug) {
        Demo d = demos.get(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("demo", d);
        out.put("currentVersion", demos.currentVersion(d).orElse(null));
        out.put("events", events.list(d.id(), 50));
        out.put("shareLinks", shareLinks.list(slug));
        if (d.managed()) {
            out.put("versions", versions.list(d.id(), 20));
            out.put("secrets", secrets.list(slug));
        }
        return out;
    }

    @GetMapping(value = "/demos/{slug}/manifest", produces = "text/yaml; charset=utf-8")
    public String manifest(@PathVariable String slug) {
        demos.requireManaged(slug);
        return jdbc.sql("select manifest_yaml from demos where slug = :s").param("s", slug).query(String.class).single();
    }

    public record RegisterRequest(@NotBlank String repo, String path, String ref) {
    }

    @PostMapping("/demos/register")
    public DemoService.RegisterResult register(@RequestBody RegisterRequest r) {
        return demos.register(r.repo().trim(), r.path(), r.ref(), Actor.currentUi());
    }

    public record ExternalRequest(String url, Map<String, String> title, Map<String, String> teaser, String category,
                                  List<String> tags) {
    }

    @PutMapping("/demos/external/{slug}")
    public Demo external(@PathVariable String slug, @RequestBody ExternalRequest r) {
        return demos.upsertExternal(slug, r.url(), r.title(), r.teaser(), r.category(), r.tags(), Actor.currentUi());
    }

    public record CatalogRequest(Integer sortOrder, Boolean featured, String screenshotUrl) {
    }

    @PatchMapping("/demos/{slug}/catalog")
    public Demo catalog(@PathVariable String slug, @RequestBody CatalogRequest r) {
        return demos.updateCatalog(slug, r.sortOrder(), r.featured(), r.screenshotUrl(), Actor.currentUi());
    }

    public record VisibilityRequest(@NotBlank String visibility) {
    }

    @PutMapping("/demos/{slug}/visibility")
    public Demo visibility(@PathVariable String slug, @RequestBody VisibilityRequest r) {
        return demos.setVisibility(slug, r.visibility(), Actor.currentUi());
    }

    public record LifecycleRequest(String lifecycle, Integer ttlDays, Integer idleMinutes, String wakePolicy,
                                   Boolean autoDeploy) {
    }

    @PatchMapping("/demos/{slug}/lifecycle")
    public Demo setLifecycle(@PathVariable String slug, @RequestBody LifecycleRequest r) {
        return demos.setLifecycle(slug, r.lifecycle(), r.ttlDays(), r.idleMinutes(), r.wakePolicy(), r.autoDeploy(),
                Actor.currentUi());
    }

    public record TtlRequest(int days) {
    }

    @PostMapping("/demos/{slug}/ttl")
    public Demo extend(@PathVariable String slug, @RequestBody TtlRequest r) {
        return demos.extendTtl(slug, r.days(), Actor.currentUi());
    }

    public record RoutingRequest(@NotBlank String routing) {
    }

    @PutMapping("/demos/{slug}/routing")
    public Demo routing(@PathVariable String slug, @RequestBody RoutingRequest r) {
        return demos.changeRouting(slug, r.routing(), Actor.currentUi());
    }

    @PostMapping("/demos/{slug}/archive")
    public Demo archive(@PathVariable String slug) {
        Demo d = demos.get(slug);
        if (d.managed() && "running".equals(d.state())) {
            lifecycle.control(slug, AgentClient.Action.stop, Actor.currentUi());
        }
        return demos.archive(slug, Actor.currentUi());
    }

    public record DeployRequest(String sha) {
    }

    @PostMapping("/demos/{slug}/deploy")
    public Map<String, Object> deploy(@PathVariable String slug, @RequestBody(required = false) DeployRequest r) {
        return deploy.deploy(slug, r == null ? null : r.sha(), Actor.currentUi());
    }

    @PostMapping("/demos/{slug}/{action:start|stop|restart|reset}")
    public Map<String, Object> control(@PathVariable String slug, @PathVariable String action) {
        return lifecycle.control(slug, AgentClient.Action.valueOf(action), Actor.currentUi());
    }

    @GetMapping("/demos/{slug}/status")
    public AgentClient.DemoStatus status(@PathVariable String slug) {
        return lifecycle.status(slug);
    }

    @GetMapping(value = "/demos/{slug}/logs", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=utf-8")
    public String logs(@PathVariable String slug, @RequestParam(defaultValue = "200") int tail) {
        return lifecycle.logs(slug, tail);
    }

    public record SecretRequest(String value) {
    }

    @PutMapping("/demos/{slug}/secrets/{name}")
    public ResponseEntity<Void> setSecret(@PathVariable String slug, @PathVariable String name, @RequestBody SecretRequest r) {
        secrets.set(slug, name, r.value(), Actor.currentUi());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/demos/{slug}/secrets/{name}")
    public ResponseEntity<Void> deleteSecret(@PathVariable String slug, @PathVariable String name) {
        secrets.delete(slug, name, Actor.currentUi());
        return ResponseEntity.noContent().build();
    }

    public record ShareLinkRequest(String label, String leadRef, Integer validDays) {
    }

    @PostMapping("/demos/{slug}/share-links")
    public ShareLinkService.Created shareLink(@PathVariable String slug, @RequestBody ShareLinkRequest r) {
        return shareLinks.create(slug, r.label(), r.leadRef(), r.validDays(), Actor.currentUi());
    }
}
