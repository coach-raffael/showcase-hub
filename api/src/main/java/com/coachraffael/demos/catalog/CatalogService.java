package com.coachraffael.demos.catalog;

import com.coachraffael.demos.common.Errors;
import com.coachraffael.demos.demo.Demo;
import com.coachraffael.demos.demo.DemoService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Öffentlicher Katalog: nur {@code visibility = public}. Diese Form liest die Homepage beim Build –
 * sie ist ein Vertrag, Felder nur ergänzen, nie umbenennen.
 */
@Service
public class CatalogService {

    public record Entry(String slug, String kind, String category, Map<String, String> title,
                        Map<String, String> teaser, Map<String, String> description, List<String> tags, String url,
                        String screenshotUrl, boolean featured, int sortOrder, String state, boolean verified,
                        OffsetDateTime updatedAt) {
    }

    public record Feed(int version, OffsetDateTime generatedAt, List<Entry> entries) {
    }

    private final DemoService demos;

    public CatalogService(DemoService demos) {
        this.demos = demos;
    }

    public Feed feed() {
        List<Entry> entries = demos.list().stream()
                .filter(d -> "public".equals(d.visibility()) && !"expired".equals(d.state()))
                .map(this::entry).toList();
        return new Feed(1, OffsetDateTime.now(), entries);
    }

    public Entry entry(String slug) {
        Demo d = demos.find(slug).filter(x -> "public".equals(x.visibility()))
                .orElseThrow(() -> Errors.notFound("Katalogeintrag", slug));
        return entry(d);
    }

    private Entry entry(Demo d) {
        // „verified“ = verwaltete Demo, deren ausgerollte Version das Qualitätsgate komplett bestanden hat
        boolean verified = d.managed() && demos.currentVersion(d).map(v -> v.publishable()).orElse(false);
        // Für Besucher zählt nur: läuft oder schläft (startet beim Öffnen)
        String state = switch (d.state()) {
            case "running" -> "running";
            case "stopped" -> "sleeping";
            default -> d.managed() ? "unavailable" : "running";
        };
        return new Entry(d.slug(), d.kind(), d.category(), d.title(), d.teaser(), d.description(), d.tags(), d.url(),
                d.screenshotUrl(), d.featured(), d.sortOrder(), state, verified, d.updatedAt());
    }
}
