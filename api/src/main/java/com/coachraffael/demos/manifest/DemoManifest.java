package com.coachraffael.demos.manifest;

import java.util.List;
import java.util.Map;

/**
 * Geprüfte Form von {@code demo.yaml} – der „Demo-Vertrag“ eines Showcases.
 * Entsteht nur über {@link ManifestParser}; alle Felder sind danach gültig.
 */
public record DemoManifest(
        String slug,
        Map<String, String> title,
        Map<String, String> teaser,
        Map<String, String> description,
        String category,
        List<String> tags,
        String visibility,
        String lifecycle,
        Integer ttlDays,
        int idleMinutes,
        String routing,
        String wake,
        Map<String, Service> services,
        Reset reset,
        List<String> envRequired,
        Quality quality) {

    /**
     * @param image     ohne Tag für eigene Images ({@code ghcr.io/coach-raffael/…}) – der Hub setzt Digest bzw. Tag;
     *                  mit festem Tag für freigegebene Basis-Images (z. B. {@code postgres:16-alpine})
     * @param expose    Pfad unterhalb der Demo, den Traefik an diesen Dienst gibt ({@code /} oder z. B. {@code /api}),
     *                  null = nur intern
     */
    public record Service(String image, Integer port, String expose, String memory, String health,
                          Map<String, String> env, List<String> volumes, List<String> dependsOn) {

        public boolean ownImage() {
            return image.startsWith(ManifestParser.OWN_IMAGE_PREFIX);
        }
    }

    public record Reset(String service, List<String> command, boolean nightly) {
    }

    public record Quality(List<String> flows, Map<String, Integer> lighthouse) {
    }

    /** Der Dienst, der unter {@code /} läuft – Ziel des Galerie-Links. */
    public String entryService() {
        return services.entrySet().stream().filter(e -> "/".equals(e.getValue().expose()))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    /** Der Health-Pfad für den Smoke-Test (relativ zur Demo), Standard {@code /}. */
    public String smokePath() {
        return services.values().stream().filter(s -> s.expose() != null && s.health() != null)
                .map(Service::health).findFirst().orElse("/");
    }
}
