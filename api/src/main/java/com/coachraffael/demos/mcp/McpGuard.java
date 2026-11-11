package com.coachraffael.demos.mcp;

import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.AuditLog;
import com.coachraffael.demos.common.HubProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/** Prüft vor jeder MCP-Aktion die Richtlinie: ALLOW → ausführen, APPROVAL → Freigabe anlegen, DENY → ablehnen. */
@Component
public class McpGuard {

    private final McpPolicyService policy;
    private final PendingActionService pending;
    private final DemoOperations operations;
    private final ObjectMapper json;
    private final HubProperties props;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    public McpGuard(McpPolicyService policy, PendingActionService pending, DemoOperations operations,
                    ObjectMapper json, HubProperties props, AuditLog audit, TransactionTemplate tx) {
        this.policy = policy;
        this.pending = pending;
        this.operations = operations;
        this.json = json;
        this.props = props;
        this.audit = audit;
        this.tx = tx;
    }

    public void checkRead() {
        if (policy.mode("read") == McpPolicyService.Mode.DENY) {
            throw new IllegalStateException("Lesen per MCP ist in der Richtlinie gesperrt (read = DENY)");
        }
    }

    public Object write(String operation, Map<String, ?> args, String summary, String idempotencyKey) {
        String tool = DemoOperations.POLICY.get(operation);
        if (tool == null) {
            throw new IllegalArgumentException("Unbekannte Operation " + operation);
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        args.forEach((k, v) -> {
            if (v != null) clean.put(k, v);
        });
        JsonNode argsJson = json.valueToTree(clean);
        Actor actor = Actor.currentMcp();
        return switch (policy.mode(tool)) {
            case ALLOW -> operations.execute(operation, argsJson, actor);
            case APPROVAL -> {
                var p = pending.request(tool, operation, argsJson, summary, actor, idempotencyKey);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "pending_approval");
                result.put("pendingActionId", p.id());
                result.put("summary", p.summary());
                result.put("message", "Freigabe angelegt – Raffael bestätigt sie unter " + url("/admin/freigaben")
                        + ". Erst danach ist die Aktion ausgeführt.");
                result.put("url", url("/admin/freigaben"));
                yield result;
            }
            case DENY -> {
                tx.executeWithoutResult(s -> audit.record(actor, "mcp.denied", "mcp_policy", null, null,
                        Map.of("tool", tool, "operation", operation, "summary", summary == null ? "" : summary)));
                throw new IllegalStateException("„" + tool + "“ ist für Claude gesperrt (Richtlinie DENY)");
            }
        };
    }

    public String url(String path) {
        return props.publicBase() + path;
    }
}
