package com.coachraffael.demos.demo;

import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.AuditLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Share-Links: öffnen ungelistete Demos und wecken schlafende auch bei {@code wake_policy = share_link}.
 * Gespeichert wird nur der SHA-256 des Tokens; das Token selbst gibt es genau einmal beim Anlegen.
 */
@Service
public class ShareLinkService {

    public record ShareLink(long id, String label, String leadRef, OffsetDateTime expiresAt, String createdBy,
                            OffsetDateTime createdAt, OffsetDateTime lastUsedAt, int uses) {
    }

    public record Created(ShareLink link, String token, String url) {
    }

    private final JdbcClient jdbc;
    private final DemoService demos;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    public ShareLinkService(JdbcClient jdbc, DemoService demos, AuditLog audit) {
        this.jdbc = jdbc;
        this.demos = demos;
        this.audit = audit;
    }

    @Transactional
    public Created create(String slug, String label, String leadRef, Integer validDays, Actor actor) {
        Demo d = demos.get(slug);
        if (validDays != null && (validDays < 1 || validDays > 365)) {
            throw new IllegalArgumentException("validDays: 1–365");
        }
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = "s_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long id = jdbc.sql("""
                        insert into share_links (demo_id, token_hash, label, lead_ref, expires_at, created_by)
                        values (:d, :h, :label, :lead, case when cast(:days as int) is null then null
                                                       else now() + make_interval(days => cast(:days as int)) end, :by)
                        returning id""")
                .param("d", d.id()).param("h", hash(token)).param("label", trim(label, 120))
                .param("lead", trim(leadRef, 120)).param("days", validDays).param("by", actor.name())
                .query(Long.class).single();
        audit.record(actor, "share_link.create", "demo", d.id(), null,
                Map.of("shareLinkId", id, "label", label == null ? "" : label));
        ShareLink link = list(slug).stream().filter(l -> l.id() == id).findFirst().orElseThrow();
        return new Created(link, token, d.url() + "?s=" + token);
    }

    @Transactional(readOnly = true)
    public List<ShareLink> list(String slug) {
        Demo d = demos.get(slug);
        return jdbc.sql("""
                        select id, label, lead_ref, expires_at, created_by, created_at, last_used_at, uses
                        from share_links where demo_id = :d order by created_at desc""")
                .param("d", d.id()).query(ShareLink.class).list();
    }

    /** Gültiges Token für diese Demo? Zählt die Nutzung mit. */
    @Transactional
    public boolean use(long demoId, String token) {
        if (token == null || !token.startsWith("s_") || token.length() > 100) return false;
        return jdbc.sql("""
                        update share_links set uses = uses + 1, last_used_at = now()
                        where demo_id = :d and token_hash = :h and (expires_at is null or expires_at > now())""")
                .param("d", demoId).param("h", hash(token)).update() == 1;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
