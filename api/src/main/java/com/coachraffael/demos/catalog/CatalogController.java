package com.coachraffael.demos.catalog;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** Öffentlicher Feed für coach-raffael.com und andere Leser. */
@RestController
public class CatalogController {

    private final CatalogService catalog;
    private final ScreenshotStore screenshots;

    public CatalogController(CatalogService catalog, ScreenshotStore screenshots) {
        this.catalog = catalog;
        this.screenshots = screenshots;
    }

    /** Screenshot eines öffentlichen Eintrags (für Galerie und Homepage). */
    @GetMapping(value = "/catalog/{slug}/screenshot.png", produces = "image/png")
    public ResponseEntity<byte[]> screenshot(@PathVariable String slug) {
        catalog.entry(slug); // nur öffentliche Einträge
        return screenshots.load(slug)
                .map(b -> ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                        .header("Access-Control-Allow-Origin", "*").body(b))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/catalog")
    public ResponseEntity<CatalogService.Feed> feed() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
                .header("Access-Control-Allow-Origin", "*").body(catalog.feed());
    }

    @GetMapping("/catalog/{slug}")
    public ResponseEntity<CatalogService.Entry> entry(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
                .header("Access-Control-Allow-Origin", "*").body(catalog.entry(slug));
    }
}
