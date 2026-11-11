package com.coachraffael.demos.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Schreibt jede ändernde Aktion mit Akteur, Kanal und Vorher/Nachher ins {@code audit_log} (nur anfügbar).
 * Läuft in der Transaktion des Aufrufers.
 */
@Component
public class AuditLog {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditLog(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(Actor actor, String action, String entityType, Long entityId, Object before, Object after) {
        jdbc.sql("""
                        insert into audit_log (actor, channel, action, entity_type, entity_id, before, after)
                        values (:actor, :channel, :action, :type, :id, cast(:before as jsonb), cast(:after as jsonb))
                        """)
                .param("actor", actor.name())
                .param("channel", actor.channel())
                .param("action", action)
                .param("type", entityType)
                .param("id", entityId)
                .param("before", toJson(before))
                .param("after", toJson(after))
                .update();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit-Eintrag nicht serialisierbar", e);
        }
    }
}
