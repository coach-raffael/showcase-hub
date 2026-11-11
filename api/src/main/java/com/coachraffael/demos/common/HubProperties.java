package com.coachraffael.demos.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Einstellungen des Hubs.
 *
 * @param publicUrl     öffentliche Adresse des Hubs, z. B. {@code https://demos.coach-raffael.com}
 * @param demoDomain    Host, unter dem Demos per Pfad laufen ({@code demos.coach-raffael.com});
 *                      Subdomain-Demos laufen unter {@code <slug>.<demoDomain>}
 * @param secretKey     Base64-Schlüssel (32 Byte) für Demo-Secrets in der Datenbank
 * @param ciToken       Token, mit dem GitHub Actions Builds und Deploy-Ergebnisse meldet (Bereich {@code ci})
 * @param agentSocket   Unix-Socket des demo-agent auf dem VPS (leer = Agent nicht verbunden)
 * @param agentKey      gemeinsamer HMAC-Schlüssel mit dem demo-agent
 * @param github        Zugang für Workflow-Starts und das Lesen von demo.yaml
 */
@ConfigurationProperties(prefix = "hub")
public record HubProperties(String publicUrl, String demoDomain, String secretKey, String ciToken,
                            String agentSocket, String agentKey, Github github) {

    /**
     * @param token          Fine-grained Token: Actions (write) auf hubRepo, Contents (write) auf homepageRepo
     * @param hubRepo        Repo mit dem Workflow deploy-demo.yml
     * @param homepageRepo   Repo der Homepage, das bei Katalogänderungen neu gebaut wird (leer = aus)
     */
    public record Github(String token, String hubRepo, String homepageRepo) {
    }

    public String publicBase() {
        return publicUrl == null ? "" : publicUrl.replaceAll("/$", "");
    }
}
