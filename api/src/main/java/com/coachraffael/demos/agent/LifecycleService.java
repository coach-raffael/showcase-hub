package com.coachraffael.demos.agent;

import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.AuditLog;
import com.coachraffael.demos.common.Errors;
import com.coachraffael.demos.demo.Demo;
import com.coachraffael.demos.demo.DemoEvents;
import com.coachraffael.demos.demo.DemoService;
import com.coachraffael.demos.demo.ShareLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hoch- und Runterfahren über den demo-agent – der Kern des Hubs.
 *
 * <p>Automatik (jede Minute):
 * <ul>
 *   <li>{@code ttl}: nach Ablauf stoppen, Zustand {@code expired} (Eintrag bleibt, Verlängern reaktiviert).</li>
 *   <li>{@code on-demand}: {@code idle_minutes} nach dem letzten Start/Wecken wieder schlafen legen.</li>
 *   <li>Abgleich: Zustand laut Docker übernehmen (z. B. Container nach einem Server-Neustart).</li>
 * </ul>
 */
@Service
public class LifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);
    /** Zustände, in denen Start/Stopp sinnvoll sind; „new“ heißt: noch nie ausgerollt. */
    private static final Set<String> CONTROLLABLE = Set.of("running", "stopped", "failed", "expired");

    public enum WakeResult { running, starting }

    private final AgentClient agent;
    private final DemoService demos;
    private final DemoEvents events;
    private final AuditLog audit;
    private final ShareLinkService shareLinks;
    private final JdbcClient jdbc;

    public LifecycleService(AgentClient agent, DemoService demos, DemoEvents events, AuditLog audit,
                            ShareLinkService shareLinks, JdbcClient jdbc) {
        this.agent = agent;
        this.demos = demos;
        this.events = events;
        this.audit = audit;
        this.shareLinks = shareLinks;
        this.jdbc = jdbc;
    }

    public Map<String, Object> control(String slug, AgentClient.Action action, Actor actor) {
        Demo d = demos.get(slug);
        if (!d.managed()) {
            throw new Errors.Conflict(slug + " ist ein externer Katalogeintrag – nichts zu steuern");
        }
        if (!CONTROLLABLE.contains(d.state())) {
            throw new Errors.Conflict(switch (d.state()) {
                case "new" -> slug + " wurde noch nie ausgerollt – erst deploy_demo";
                case "deploying" -> slug + " wird gerade ausgerollt – kurz warten";
                default -> slug + " ist im Zustand " + d.state();
            });
        }
        if ("expired".equals(d.state()) && action != AgentClient.Action.stop
                && "ttl".equals(d.lifecycle()) && d.expiresAt() != null && d.expiresAt().isBefore(OffsetDateTime.now())) {
            throw new Errors.Conflict("TTL von " + slug + " ist abgelaufen – erst verlängern (extend_ttl)");
        }
        Map<String, Object> result;
        try {
            result = agent.run(slug, action);
        } catch (RuntimeException e) {
            events.record(d.id(), action.name(), actor, false, Map.of("error", String.valueOf(e.getMessage())));
            throw e;
        }
        switch (action) {
            case start, restart -> {
                demos.setState(d.id(), "running");
                jdbc.sql("update demos set last_woken_at = now() where id = :id").param("id", d.id()).update();
            }
            case stop -> demos.setState(d.id(), "stopped");
            case reset -> { /* Zustand bleibt */ }
        }
        events.record(d.id(), action.name(), actor, true, null);
        audit.record(actor, "demo." + action.name(), "demo", d.id(), Map.of("state", d.state()),
                Map.of("state", demos.get(slug).state()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", slug);
        out.put("action", action.name());
        out.put("state", demos.get(slug).state());
        out.put("agent", result);
        out.put("url", d.url());
        return out;
    }

    /** Status laut Docker; übernimmt dabei den Zustand in den Hub. */
    public AgentClient.DemoStatus status(String slug) {
        Demo d = demos.get(slug);
        if (!d.managed()) {
            throw new Errors.Conflict(slug + " ist ein externer Katalogeintrag");
        }
        AgentClient.DemoStatus s = agent.status(slug);
        sync(d, s);
        return s;
    }

    public String logs(String slug, int tail) {
        Demo d = demos.get(slug);
        if (!d.managed()) {
            throw new Errors.Conflict(slug + " ist ein externer Katalogeintrag");
        }
        return agent.logs(slug, tail);
    }

    /**
     * Wecken von der Schlafseite (öffentlich). Private Demos gibt es für Besucher nicht; je nach
     * {@code wake_policy} genügt der Aufruf, braucht es einen Share-Link oder geht es gar nicht.
     */
    public WakeResult wake(String slug, String shareToken) {
        Demo d = demos.find(slug).filter(x -> x.managed() && !"private".equals(x.visibility()))
                .orElseThrow(() -> Errors.notFound("Demo", slug));
        boolean viaLink = shareToken != null && !shareToken.isBlank() && shareLinks.use(d.id(), shareToken);
        switch (d.wakePolicy()) {
            case "anyone" -> { }
            case "share_link" -> {
                if (!viaLink) throw new Errors.Conflict("Diese Demo startet nur über einen persönlichen Link");
            }
            default -> throw new Errors.Conflict("Diese Demo wird von Raffael gestartet – bitte kurz melden");
        }
        if ("running".equals(d.state())) {
            return WakeResult.running;
        }
        if (!"stopped".equals(d.state())) {
            throw new Errors.Conflict("Diese Demo ist gerade nicht verfügbar");
        }
        // Mehrfaches Klicken: ein Start genügt
        int claimed = jdbc.sql("""
                        update demos set last_woken_at = now()
                        where id = :id and (last_woken_at is null or last_woken_at < now() - interval '20 seconds')""")
                .param("id", d.id()).update();
        if (claimed == 0) {
            return WakeResult.starting;
        }
        Actor visitor = viaLink ? new Actor("share-link", "automation") : Actor.visitor();
        try {
            agent.run(slug, AgentClient.Action.start);
            demos.setState(d.id(), "running");
            events.record(d.id(), "wake", visitor, true, null);
        } catch (RuntimeException e) {
            events.record(d.id(), "wake", visitor, false, Map.of("error", String.valueOf(e.getMessage())));
            throw new Errors.Conflict("Die Demo ließ sich gerade nicht starten");
        }
        return WakeResult.starting;
    }

    // ---------- Automatik ----------

    @Scheduled(fixedDelayString = "${hub.reaper-interval-ms:60000}", initialDelayString = "${hub.reaper-initial-delay-ms:30000}")
    public void housekeeping() {
        if (!agent.configured()) {
            return;
        }
        expireTtl();
        sleepIdle();
        syncAll();
    }

    void expireTtl() {
        List<String> due = jdbc.sql("""
                        select slug from demos where kind = 'managed' and lifecycle = 'ttl' and expires_at < now()
                          and state in ('running', 'stopped')""")
                .query(String.class).list();
        for (String slug : due) {
            Demo d = demos.get(slug);
            try {
                if ("running".equals(d.state())) agent.run(slug, AgentClient.Action.stop);
                demos.setState(d.id(), "expired");
                events.record(d.id(), "expire", Actor.system(), true, Map.of("expiresAt", String.valueOf(d.expiresAt())));
            } catch (RuntimeException e) {
                log.warn("TTL-Ablauf {} fehlgeschlagen: {}", slug, e.getMessage());
            }
        }
    }

    void sleepIdle() {
        List<String> idle = jdbc.sql("""
                        select slug from demos where kind = 'managed' and lifecycle = 'on-demand' and state = 'running'
                          and coalesce(last_woken_at, state_at) < now() - make_interval(mins => idle_minutes)""")
                .query(String.class).list();
        for (String slug : idle) {
            Demo d = demos.get(slug);
            try {
                agent.run(slug, AgentClient.Action.stop);
                demos.setState(d.id(), "stopped");
                events.record(d.id(), "sleep", Actor.system(), true, Map.of("idleMinutes", d.idleMinutes()));
            } catch (RuntimeException e) {
                log.warn("Schlafenlegen {} fehlgeschlagen: {}", slug, e.getMessage());
            }
        }
    }

    void syncAll() {
        try {
            for (AgentClient.DemoStatus s : agent.statusAll()) {
                demos.find(s.slug()).filter(Demo::managed).ifPresent(d -> sync(d, s));
            }
        } catch (RuntimeException e) {
            log.warn("Abgleich mit dem demo-agent fehlgeschlagen: {}", e.getMessage());
        }
    }

    /** Nur running ↔ stopped abgleichen; deploying/expired/new bestimmt der Hub. */
    void sync(Demo d, AgentClient.DemoStatus s) {
        if (!Set.of("running", "stopped", "failed").contains(d.state())) return;
        String actual = switch (s.state()) {
            case "running" -> "running";
            case "stopped", "exited", "created" -> "stopped";
            case "partial", "restarting", "unhealthy" -> "failed";
            default -> null;
        };
        if (actual != null && !actual.equals(d.state())) {
            demos.setState(d.id(), actual);
            events.record(d.id(), "sync", Actor.system(), true, Map.of("from", d.state(), "to", actual));
        }
    }
}
