package com.coachraffael.demos.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Macht aus den Keycloak-Realm-Rollen ({@code realm_access.roles}) Spring-Authorities {@code ROLE_<name>},
 * z.B. {@code ROLE_admin}, {@code ROLE_demos-admin}. Client-Rollen ({@code resource_access}) und Scopes
 * werden bewusst ignoriert – Berechtigungen hängen in dieser Plattform nur an Realm-Rollen.
 */
public class KeycloakRealmRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String REALM_ACCESS = "realm_access";
    static final String ROLES = "roles";
    static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        return realmRoles(jwt).stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
    }

    /** Die Realm-Rollen aus dem Token, leer wenn der Claim fehlt oder anders geformt ist. */
    public static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS);
        if (realmAccess == null) {
            return List.of();
        }
        Object roles = realmAccess.get(ROLES);
        if (!(roles instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !role.isBlank())
                .toList();
    }
}
