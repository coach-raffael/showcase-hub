package com.coachraffael.demos.security;

import com.coachraffael.demos.common.HubProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Fehlerantworten der Security-Schicht als JSON statt HTML/Leerkörper:
 * 401 {@code {"error":"unauthorized"}} bei fehlendem/ungültigem Token,
 * 403 {@code {"error":"forbidden"}} bei fehlender Rolle.
 */
@Component
public class JsonAuthErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String UNAUTHORIZED_BODY = "{\"error\":\"unauthorized\"}";
    private static final String FORBIDDEN_BODY = "{\"error\":\"forbidden\"}";

    private final String resourceMetadataUrl;

    public JsonAuthErrorHandlers(HubProperties props) {
        String base = props.publicUrl() == null ? "" : props.publicUrl().replaceAll("/$", "");
        // RFC 9728: MCP-Clients (claude.ai) finden darüber den Keycloak-Realm als Autorisierungsserver
        this.resourceMetadataUrl = base + "/.well-known/oauth-protected-resource/api/mcp";
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // Bearer-Hinweis nach RFC 6750, ohne Fehlerdetails nach außen zu tragen
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"" + resourceMetadataUrl + "\"");
        write(response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, FORBIDDEN_BODY);
    }

    private static void write(HttpServletResponse response, HttpStatus status, String body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
        response.getWriter().flush();
    }
}
