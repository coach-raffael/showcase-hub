package com.coachraffael.demos.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/** GitHub Actions mit {@code HUB_CI_TOKEN}: meldet Builds, holt Deploy-Bündel, meldet Ergebnisse. */
public class CiTokenAuthentication extends AbstractAuthenticationToken {

    public CiTokenAuthentication() {
        super(List.of(new SimpleGrantedAuthority("SCOPE_ci")));
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return "github-actions";
    }
}
