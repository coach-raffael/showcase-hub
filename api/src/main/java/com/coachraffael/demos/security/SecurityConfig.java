package com.coachraffael.demos.security;

import com.coachraffael.demos.common.HubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;

/**
 * Stateless Resource-Server gegen Keycloak (Realm {@code coach-raffael}).
 * <ul>
 *   <li>Öffentlich: Health, Katalog-Feed, Status/Wecken einzelner Demos (für die Schlafseite).</li>
 *   <li>GitHub Actions ({@code hubci_…}): nur {@code /ci/**}.</li>
 *   <li>Alles andere inkl. MCP: Realm-Rolle {@code demos-admin}.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    public static final String ADMIN_ROLE = "demos-admin";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
                                    JsonAuthErrorHandlers errorHandlers, HubProperties props) throws Exception {
        DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();
        http
                .addFilterBefore(new CiTokenFilter(props.ciToken()), BearerTokenAuthenticationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/health", "/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/catalog", "/catalog/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/public/demos/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/public/demos/*/wake").permitAll()
                        .requestMatchers("/ci/**").hasAuthority("SCOPE_ci")
                        .anyRequest().hasRole(ADMIN_ROLE))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(request -> {
                            String token = bearer.resolve(request);
                            return CiTokenFilter.isCiToken(token) ? null : token;
                        })
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(errorHandlers).accessDeniedHandler(errorHandlers));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRolesConverter());
        return converter;
    }

    /** Lazy: OIDC-Metadaten werden erst beim ersten Token geladen – Start und Tests ohne Keycloak möglich. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
                          AuthProperties authProperties) {
        return new SupplierJwtDecoder(() -> {
            log.info("Initialisiere JwtDecoder für Issuer {} (Audience {})", issuerUri, authProperties.audience());
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
            decoder.setJwtValidator(tokenValidator(issuerUri, authProperties.audience()));
            return decoder;
        });
    }

    static OAuth2TokenValidator<Jwt> tokenValidator(String issuerUri, String audience) {
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> withAudience = new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience);
    }
}
