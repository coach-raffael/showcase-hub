package com.coachraffael.demos.mcp;

import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.AuditLog;
import com.coachraffael.demos.common.Errors;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Freigaben: Claude beantragt eine Operation (Richtlinie APPROVAL), Raffael bestätigt oder lehnt ab.
 * Beim Bestätigen läuft die gespeicherte Operation über {@link DemoOperations} – derselbe Weg wie bei ALLOW.
 */
@Service
public class PendingActionService {

    public record PendingAction(long id, String tool, String operation, @JsonRawValue String arguments,
                                String summary, String status, OffsetDateTime requestedAt, String requestedBy,
                                OffsetDateTime decidedAt, String decidedBy, @JsonRawValue String result) {
    }

    private static final String SELECT = """
            select id, tool, arguments->>'operation' as operation, (arguments->'args')::text as arguments, summary,
                   status, requested_at, requested_by, decided_at, decided_by, result::text as result
            from pending_actions
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final DemoOperations operations;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    public PendingActionService(JdbcClient jdbc, ObjectMapper json, DemoOperations operations, AuditLog audit,
                                TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.operations = operations;
        this.audit = audit;
        this.tx = tx;
    }

    /** Legt die Freigabe an; mit gleichem Idempotenzschlüssel wird die bestehende zurückgegeben. */
    @Transactional
    public PendingAction request(String tool, String operation, JsonNode args, String summary, Actor actor,
                                 String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = jdbc.sql(SELECT + " where idempotency_key = :k").param("k", idempotencyKey)
                    .query(PendingAction.class).optional();
            if (existing.isPresent()) {
                // Offene oder ausgeführte Freigabe: dieselbe zurückgeben. Gescheiterte oder abgelehnte blockieren
                // keine Wiederholung – ihr Schlüssel wird freigegeben (Original bleibt im Verlauf erkennbar).
                if (!Set.of("failed", "rejected").contains(existing.get().status())) {
                    return existing.get();
                }
                jdbc.sql("update pending_actions set idempotency_key = idempotency_key || '#' || id where id = :id")
                        .param("id", existing.get().id()).update();
            }
        }
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        insert into pending_actions (tool, arguments, summary, idempotency_key, requested_by)
                        values (:tool, cast(:args as jsonb), :summary, :key, :by)""")
                .param("tool", tool)
                .param("args", json.createObjectNode().put("operation", operation).set("args", args).toString())
                .param("summary", summary)
                .param("key", idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey)
                .param("by", actor.name())
                .update(keys, "id");
        long id = keys.getKey().longValue();
        audit.record(actor, "pending_action.request", "pending_action", id, null,
                Map.of("tool", tool, "operation", operation, "summary", summary));
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<PendingAction> list(String status) {
        return jdbc.sql(SELECT + " where (cast(:s as text) is null or status = :s) order by requested_at desc limit 200")
                .param("s", status).query(PendingAction.class).list();
    }

    @Transactional(readOnly = true)
    public PendingAction get(long id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(PendingAction.class).optional()
                .orElseThrow(() -> Errors.notFound("Freigabe", id));
    }

    /**
     * Bestätigen und ausführen. Scheitert die Operation (z. B. Gate inzwischen rot), bleibt das
     * fachlich unverändert; die Freigabe wird als {@code failed} mit der Fehlermeldung abgelegt.
     */
    public PendingAction approve(long id, Actor approver) {
        record Claimed(String tool, String operation, String args) {
        }
        Claimed c = tx.execute(status -> {
            Claimed row = jdbc.sql("""
                            select tool, arguments->>'operation' as operation, (arguments->'args')::text as args
                            from pending_actions where id = :id and status = 'pending' for update""")
                    .param("id", id).query(Claimed.class).optional()
                    .orElseThrow(() -> new Errors.Conflict("Freigabe " + id + " ist nicht mehr offen"));
            jdbc.sql("update pending_actions set status = 'approved', decided_at = now(), decided_by = :by where id = :id")
                    .param("by", approver.name()).param("id", id).update();
            audit.record(approver, "pending_action.approve", "pending_action", id, null,
                    java.util.Map.of("tool", row.tool(), "operation", row.operation()));
            return row;
        });

        Actor executor = new Actor("claude (freigegeben von " + approver.name() + ", #" + id + ")", "mcp");
        JsonNode args;
        try {
            args = json.readTree(c.args());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            store(id, "failed", json.createObjectNode().put("error", "Gespeicherte Argumente nicht lesbar"));
            return get(id);
        }
        try {
            // Lebenszyklus-Aktionen rufen den Agenten – nicht in einer offenen DB-Transaktion halten
            Object result = operations.execute(c.operation(), args, executor);
            store(id, "executed", resultTree(result));
        } catch (RuntimeException e) {
            store(id, "failed", json.createObjectNode().put("error", rootMessage(e)));
            tx.executeWithoutResult(st -> audit.record(executor, "pending_action.failed", "pending_action", id, null,
                    java.util.Map.of("operation", c.operation(), "error", rootMessage(e))));
        }
        return get(id);
    }

    public record BulkResult(int executed, int failed, List<PendingAction> results) {
    }

    /**
     * Alle offenen (oder die genannten) Freigaben nacheinander bestätigen – in der Reihenfolge, in der Claude sie
     * beantragt hat, damit z. B. erst registriert und dann freigeschaltet wird. Jede läuft für sich;
     * scheitert eine, laufen die übrigen weiter.
     */
    public BulkResult approveAll(List<Long> ids, Actor approver) {
        List<Long> todo = jdbc.sql("""
                        select id from pending_actions where status = 'pending'
                          and (cast(:ids as jsonb) is null
                               or id = any(array(select jsonb_array_elements_text(cast(:ids as jsonb))::bigint)))
                        order by requested_at, id""")
                .param("ids", ids == null || ids.isEmpty() ? null : json.valueToTree(ids).toString())
                .query(Long.class).list();
        List<PendingAction> results = new java.util.ArrayList<>();
        int executed = 0;
        int failed = 0;
        for (Long id : todo) {
            PendingAction r;
            try {
                r = approve(id, approver);
            } catch (Errors.Conflict e) {
                continue; // inzwischen von anderswo entschieden
            }
            results.add(r);
            if ("executed".equals(r.status())) executed++;
            else failed++;
        }
        return new BulkResult(executed, failed, results);
    }

    @Transactional
    public PendingAction reject(long id, Actor approver) {
        int n = jdbc.sql("""
                        update pending_actions set status = 'rejected', decided_at = now(), decided_by = :by
                        where id = :id and status = 'pending'""")
                .param("by", approver.name()).param("id", id).update();
        if (n == 0) {
            throw new Errors.Conflict("Freigabe " + id + " ist nicht mehr offen");
        }
        audit.record(approver, "pending_action.reject", "pending_action", id, null, null);
        return get(id);
    }

    private JsonNode resultTree(Object result) {
        try {
            return json.readTree(json.writeValueAsString(result));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return json.createObjectNode().put("info", "ausgeführt");
        }
    }

    private void store(long id, String status, JsonNode result) {
        tx.executeWithoutResult(s -> jdbc.sql("update pending_actions set status = :st, result = cast(:r as jsonb) where id = :id")
                .param("st", status).param("r", result.toString()).param("id", id).update());
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        String msg = e.getMessage();
        while (t != null) {
            if (t.getMessage() != null) {
                msg = t.getMessage();
            }
            if (t instanceof java.sql.SQLException) {
                break;
            }
            t = t.getCause();
        }
        return msg == null ? e.getClass().getSimpleName() : msg.replaceFirst("^ERROR: ", "").split("\n")[0];
    }
}
