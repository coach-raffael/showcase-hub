package com.coachraffael.demos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Erkennt {@code Authorization: Bearer hubci_…} und setzt {@link CiTokenAuthentication}, wenn das Token
 * dem Secret {@code HUB_CI_TOKEN} entspricht (Vergleich in konstanter Zeit). Andere Bearer-Token prüft Keycloak.
 */
public class CiTokenFilter extends OncePerRequestFilter {

    public static final String PREFIX = "hubci_";
    private final byte[] expected;

    public CiTokenFilter(String token) {
        this.expected = token == null || token.isBlank() ? null : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer " + PREFIX)) {
            byte[] given = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
            if (expected != null && MessageDigest.isEqual(expected, given)) {
                SecurityContextHolder.getContext().setAuthentication(new CiTokenAuthentication());
            }
        }
        chain.doFilter(request, response);
    }

    public static boolean isCiToken(String bearer) {
        return bearer != null && bearer.startsWith(PREFIX);
    }
}
