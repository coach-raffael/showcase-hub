package com.coachraffael.demos.demo;

import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.AuditLog;
import com.coachraffael.demos.common.Errors;
import com.coachraffael.demos.common.SecretBox;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Werte für {@code env_required} einer Demo. Verschlüsselt gespeichert, nie per API oder MCP zurückgegeben –
 * nur das Deploy-Bündel für GitHub Actions enthält sie (Bereich {@code ci}). Setzen nur in der Oberfläche.
 */
@Service
public class SecretService {

    private static final Pattern NAME = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    public record SecretInfo(String name, boolean set, boolean required, OffsetDateTime updatedAt) {
    }

    private final JdbcClient jdbc;
    private final SecretBox box;
    private final DemoService demos;
    private final AuditLog audit;

    public SecretService(JdbcClient jdbc, SecretBox box, DemoService demos, AuditLog audit) {
        this.jdbc = jdbc;
        this.box = box;
        this.demos = demos;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SecretInfo> list(String slug) {
        Demo d = demos.requireManaged(slug);
        List<String> required = demos.manifest(slug).envRequired();
        record Row(String name, OffsetDateTime updatedAt) {
        }
        Map<String, Row> set = new LinkedHashMap<>();
        jdbc.sql("select name, updated_at from demo_secrets where demo_id = :d order by name").param("d", d.id())
                .query(Row.class).list().forEach(r -> set.put(r.name(), r));
        java.util.ArrayList<SecretInfo> out = new java.util.ArrayList<>();
        for (String r : required) {
            Row row = set.remove(r);
            out.add(new SecretInfo(r, row != null, true, row == null ? null : row.updatedAt()));
        }
        set.values().forEach(r -> out.add(new SecretInfo(r.name(), true, false, r.updatedAt())));
        return out;
    }

    @Transactional
    public void set(String slug, String name, String value, Actor actor) {
        if (!"ui".equals(actor.channel())) {
            throw new Errors.Conflict("Secrets lassen sich nur in der Oberfläche setzen");
        }
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Name: GROSSBUCHSTABEN_MIT_UNTERSTRICH");
        }
        if (value == null || value.isEmpty() || value.length() > 4096) {
            throw new IllegalArgumentException("Wert: 1–4096 Zeichen");
        }
        if (value.contains("'") || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("Wert darf weder ' noch Zeilenumbrüche enthalten (.env-Format)");
        }
        Demo d = demos.requireManaged(slug);
        jdbc.sql("""
                        insert into demo_secrets (demo_id, name, value_enc) values (:d, :n, :v)
                        on conflict (demo_id, name) do update set value_enc = excluded.value_enc, updated_at = now()""")
                .param("d", d.id()).param("n", name).param("v", box.encrypt(value)).update();
        audit.record(actor, "secret.set", "demo", d.id(), null, Map.of("name", name));
    }

    @Transactional
    public void delete(String slug, String name, Actor actor) {
        if (!"ui".equals(actor.channel())) {
            throw new Errors.Conflict("Secrets lassen sich nur in der Oberfläche löschen");
        }
        Demo d = demos.requireManaged(slug);
        int n = jdbc.sql("delete from demo_secrets where demo_id = :d and name = :n").param("d", d.id())
                .param("n", name).update();
        if (n == 0) throw Errors.notFound("Secret", name);
        audit.record(actor, "secret.delete", "demo", d.id(), Map.of("name", name), null);
    }

    /** Entschlüsselte Werte für das Deploy-Bündel; fehlt ein Pflichtwert, wird nicht ausgerollt. */
    @Transactional(readOnly = true)
    public Map<String, String> resolve(Demo d, List<String> required) {
        record Row(String name, String valueEnc) {
        }
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("select name, value_enc from demo_secrets where demo_id = :d").param("d", d.id()).query(Row.class)
                .list().forEach(r -> out.put(r.name(), box.decrypt(r.valueEnc())));
        List<String> missing = required.stream().filter(r -> !out.containsKey(r)).toList();
        if (!missing.isEmpty()) {
            throw new Errors.Conflict("Secrets fehlen für " + d.slug() + ": " + missing + " – im Hub unter Secrets setzen");
        }
        return out;
    }
}
