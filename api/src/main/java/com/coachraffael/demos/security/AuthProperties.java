package com.coachraffael.demos.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Einstellungen der Token-Prüfung.
 *
 * @param audience Wert, der im {@code aud}-Claim des Access-Tokens enthalten sein muss (Keycloak-Client "demos-api")
 */
@ConfigurationProperties(prefix = "hub.auth")
public record AuthProperties(String audience) {
}
