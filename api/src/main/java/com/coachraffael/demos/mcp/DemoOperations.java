package com.coachraffael.demos.mcp;

import com.coachraffael.demos.agent.AgentClient;
import com.coachraffael.demos.agent.LifecycleService;
import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.deploy.DeployService;
import com.coachraffael.demos.demo.DemoService;
import com.coachraffael.demos.demo.ShareLinkService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Alle schreibenden MCP-Operationen an einer Stelle – ausgeführt sofort (ALLOW) oder nach Freigabe (APPROVAL).
 * Ruft dieselben Services wie die Oberfläche; kein Tool schreibt an ihnen vorbei.
 */
@Component
public class DemoOperations {

    /** Operation → Richtlinie in {@code mcp_policy}. */
    public static final Map<String, String> POLICY = Map.ofEntries(
            Map.entry("register_demo", "register_demo"),
            Map.entry("upsert_external", "register_demo"),
            Map.entry("deploy_demo", "deploy_demo"),
            Map.entry("start_demo", "control_demo"),
            Map.entry("stop_demo", "control_demo"),
            Map.entry("restart_demo", "control_demo"),
            Map.entry("reset_demo", "reset_demo"),
            Map.entry("update_catalog", "update_catalog"),
            Map.entry("set_lifecycle", "set_lifecycle"),
            Map.entry("extend_ttl", "set_lifecycle"),
            Map.entry("create_share_link", "create_share_link"),
            Map.entry("set_visibility", "set_visibility"),
            Map.entry("change_routing", "change_routing"));

    private final DemoService demos;
    private final DeployService deploy;
    private final LifecycleService lifecycle;
    private final ShareLinkService shareLinks;
    private final ObjectMapper json;

    public DemoOperations(DemoService demos, DeployService deploy, LifecycleService lifecycle,
                          ShareLinkService shareLinks, ObjectMapper json) {
        this.demos = demos;
        this.deploy = deploy;
        this.lifecycle = lifecycle;
        this.shareLinks = shareLinks;
        this.json = json;
    }

    public Object execute(String operation, JsonNode a, Actor actor) {
        return switch (operation) {
            case "register_demo" -> demos.register(text(a, "repo"), text(a, "path"), text(a, "ref"), actor);
            case "upsert_external" -> demos.upsertExternal(text(a, "slug"), text(a, "url"), map(a, "title"),
                    map(a, "teaser"), text(a, "category"), list(a, "tags"), actor);
            case "deploy_demo" -> deploy.deploy(text(a, "slug"), text(a, "sha"), actor);
            case "start_demo" -> lifecycle.control(text(a, "slug"), AgentClient.Action.start, actor);
            case "stop_demo" -> lifecycle.control(text(a, "slug"), AgentClient.Action.stop, actor);
            case "restart_demo" -> lifecycle.control(text(a, "slug"), AgentClient.Action.restart, actor);
            case "reset_demo" -> lifecycle.control(text(a, "slug"), AgentClient.Action.reset, actor);
            case "update_catalog" -> demos.updateCatalog(text(a, "slug"), integer(a, "sortOrder"),
                    bool(a, "featured"), null, actor);
            case "set_lifecycle" -> demos.setLifecycle(text(a, "slug"), text(a, "lifecycle"), integer(a, "ttlDays"),
                    integer(a, "idleMinutes"), text(a, "wakePolicy"), bool(a, "autoDeploy"), actor);
            case "extend_ttl" -> demos.extendTtl(text(a, "slug"), integer(a, "days"), actor);
            case "create_share_link" -> shareLinks.create(text(a, "slug"), text(a, "label"), text(a, "leadRef"),
                    integer(a, "validDays"), actor);
            case "set_visibility" -> demos.setVisibility(text(a, "slug"), text(a, "visibility"), actor);
            case "change_routing" -> demos.changeRouting(text(a, "slug"), text(a, "routing"), actor);
            default -> throw new IllegalArgumentException("Unbekannte Operation " + operation);
        };
    }

    private static String text(JsonNode a, String f) {
        JsonNode v = a.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer integer(JsonNode a, String f) {
        JsonNode v = a.get(f);
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static Boolean bool(JsonNode a, String f) {
        JsonNode v = a.get(f);
        return v == null || v.isNull() ? null : v.asBoolean();
    }

    private Map<String, String> map(JsonNode a, String f) {
        JsonNode v = a.get(f);
        return v == null || v.isNull() ? null : json.convertValue(v, new TypeReference<>() {
        });
    }

    private List<String> list(JsonNode a, String f) {
        JsonNode v = a.get(f);
        return v == null || v.isNull() ? null : json.convertValue(v, new TypeReference<>() {
        });
    }
}
