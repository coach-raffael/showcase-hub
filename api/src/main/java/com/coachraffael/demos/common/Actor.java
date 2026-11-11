package com.coachraffael.demos.common;

import com.coachraffael.demos.security.CiTokenAuthentication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Wer eine Änderung auslöst und über welchen Kanal – landet in Audit-Log und Demo-Ereignissen.
 *
 * @param name    Benutzername (Keycloak {@code preferred_username}, sonst {@code sub}) oder Systemname
 * @param channel {@code ui}, {@code mcp}, {@code system}, {@code ci} (GitHub Actions) oder {@code automation}
 */
public record Actor(String name, String channel) {

    public static Actor system() {
        return new Actor("system", "system");
    }

    public static Actor visitor() {
        return new Actor("besucher", "automation");
    }

    public static Actor currentUi() {
        return current("ui");
    }

    public static Actor currentMcp() {
        Actor a = current("mcp");
        return "anonymous".equals(a.name()) ? new Actor("claude", "mcp") : new Actor("claude via " + a.name(), "mcp");
    }

    private static Actor current(String channel) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return new Actor("anonymous", channel);
        }
        if (auth instanceof CiTokenAuthentication) {
            return new Actor("github-actions", "ci");
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            String username = jwt.getClaimAsString("preferred_username");
            return new Actor(username != null ? username : jwt.getSubject(), channel);
        }
        return new Actor(auth.getName(), channel);
    }
}
