package com.coachraffael.demos.mcp;

import com.coachraffael.demos.common.Actor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/** Richtlinie, Freigaben und Audit für die Oberfläche. */
@RestController
public class McpAdminController {

    private final McpPolicyService policy;
    private final PendingActionService pending;
    private final JdbcClient jdbc;

    public McpAdminController(McpPolicyService policy, PendingActionService pending, JdbcClient jdbc) {
        this.policy = policy;
        this.pending = pending;
        this.jdbc = jdbc;
    }

    @GetMapping("/mcp-policy")
    public List<McpPolicyService.Policy> policies() {
        return policy.list();
    }

    public record PolicyRequest(McpPolicyService.Mode mode) {
    }

    @PutMapping("/mcp-policy/{tool}")
    public McpPolicyService.Policy update(@PathVariable String tool, @RequestBody PolicyRequest r) {
        return policy.update(tool, r.mode(), Actor.currentUi());
    }

    @GetMapping("/approvals")
    public List<PendingActionService.PendingAction> approvals(@RequestParam(required = false) String status) {
        return pending.list(status);
    }

    @PostMapping("/approvals/{id}/approve")
    public PendingActionService.PendingAction approve(@PathVariable long id) {
        return pending.approve(id, Actor.currentUi());
    }

    @PostMapping("/approvals/{id}/reject")
    public PendingActionService.PendingAction reject(@PathVariable long id) {
        return pending.reject(id, Actor.currentUi());
    }

    public record AuditEntry(long id, OffsetDateTime at, String actor, String channel, String action,
                             String entityType, Long entityId,
                             @com.fasterxml.jackson.annotation.JsonRawValue String before,
                             @com.fasterxml.jackson.annotation.JsonRawValue String after) {
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestParam(defaultValue = "100") int limit) {
        return jdbc.sql("""
                        select id, at, actor, channel, action, entity_type, entity_id, before::text as before,
                               after::text as after
                        from audit_log order by id desc limit :l""")
                .param("l", Math.max(1, Math.min(limit, 500))).query(AuditEntry.class).list();
    }
}
