package com.coachraffael.demos.mcp;

import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.AuditLog;
import com.coachraffael.demos.common.Errors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Richtlinie je MCP-Tool: ALLOW / APPROVAL / DENY. Fest gesperrte Einträge sind nicht umstellbar. */
@Service
public class McpPolicyService {

    public enum Mode { ALLOW, APPROVAL, DENY }

    public record Policy(String tool, String mode, boolean locked, String description, OffsetDateTime updatedAt,
                         String updatedBy) {
    }

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public McpPolicyService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Unbekannte Tools gelten als gesperrt. */
    @Transactional(readOnly = true)
    public Mode mode(String tool) {
        return jdbc.sql("select mode from mcp_policy where tool = :t").param("t", tool)
                .query(String.class).optional().map(Mode::valueOf).orElse(Mode.DENY);
    }

    @Transactional(readOnly = true)
    public List<Policy> list() {
        return jdbc.sql("""
                        select tool, mode, locked, description, updated_at, updated_by from mcp_policy
                        order by case mode when 'ALLOW' then 0 when 'APPROVAL' then 1 else 2 end, tool""")
                .query(Policy.class).list();
    }

    /** Nur über die Oberfläche – ein MCP-Aufruf darf die eigene Richtlinie nie ändern. */
    @Transactional
    public Policy update(String tool, Mode mode, Actor actor) {
        if (!"ui".equals(actor.channel())) {
            throw new Errors.Conflict("Die MCP-Richtlinie lässt sich nur in der Oberfläche ändern");
        }
        Policy before = jdbc.sql("select tool, mode, locked, description, updated_at, updated_by from mcp_policy where tool = :t for update")
                .param("t", tool).query(Policy.class).optional()
                .orElseThrow(() -> Errors.notFound("MCP-Tool", tool));
        if (before.locked()) {
            throw new Errors.Conflict("„" + tool + "“ ist fest gesperrt und nicht umstellbar");
        }
        jdbc.sql("update mcp_policy set mode = :m, updated_at = now(), updated_by = :by where tool = :t")
                .param("m", mode.name()).param("by", actor.name()).param("t", tool).update();
        audit.record(actor, "mcp_policy.update", "mcp_policy", null, Map.of("tool", tool, "mode", before.mode()),
                Map.of("tool", tool, "mode", mode.name()));
        return list().stream().filter(p -> p.tool().equals(tool)).findFirst().orElseThrow();
    }
}
