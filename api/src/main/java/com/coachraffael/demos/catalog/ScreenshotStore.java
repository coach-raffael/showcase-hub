package com.coachraffael.demos.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/** Screenshots der Demos (PNG, nach jedem Deploy von Playwright) im Datenverzeichnis des Hubs. */
@Component
public class ScreenshotStore {

    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");

    private final Path dir;

    public ScreenshotStore(@Value("${hub.data-dir:${HUB_DATA_DIR:../data}}") String dataDir) {
        this.dir = Path.of(dataDir, "screenshots");
    }

    public void save(String slug, byte[] png) {
        check(slug);
        if (png == null || png.length < PNG.length || png.length > MAX_BYTES) {
            throw new IllegalArgumentException("Screenshot: PNG bis 2 MB");
        }
        for (int i = 0; i < PNG.length; i++) {
            if (png[i] != PNG[i]) throw new IllegalArgumentException("Screenshot ist kein PNG");
        }
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, slug, ".part");
            Files.write(tmp, png);
            Files.move(tmp, dir.resolve(slug + ".png"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Screenshot nicht speicherbar: " + e.getMessage(), e);
        }
    }

    public Optional<byte[]> load(String slug) {
        check(slug);
        Path p = dir.resolve(slug + ".png");
        try {
            return Files.exists(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void check(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) throw new IllegalArgumentException("Slug ungültig");
    }
}
