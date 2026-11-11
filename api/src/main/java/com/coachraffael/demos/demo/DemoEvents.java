package com.coachraffael.demos.demo;

import com.coachraffael.demos.common.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Betriebsprotokoll je Demo (Start, Stopp, Deploy, Reset, Wecken). Eigene Transaktion: auch ein gescheiterter
 * Start soll sichtbar bleiben.
 */
@Component
public class DemoEvents {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DemoEvents(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long demoId, String action, Actor actor, boolean ok, Object detail) {
        String d;
        try {
            d = detail == null ? null : json.writeValueAsString(detail);
        } catch (Exception e) {
            d = null;
        }
        jdbc.sql("""
                        insert into demo_events (demo_id, action, actor, channel, ok, detail)
                        values (:d, :a, :actor, :ch, :ok, cast(:detail as jsonb))""")
                .param("d", demoId).param("a", action).param("actor", actor.name()).param("ch", actor.channel())
                .param("ok", ok).param("detail", d).update();
    }

    @Transactional(readOnly = true)
    public List<DemoEvent> list(long demoId, int limit) {
        return jdbc.sql("""
                        select id, action, actor, channel, ok, detail::text as detail, at from demo_events
                        where demo_id = :d order by at desc, id desc limit :l""")
                .param("d", demoId).param("l", Math.max(1, Math.min(limit, 500))).query(DemoEvent.class).list();
    }
}
