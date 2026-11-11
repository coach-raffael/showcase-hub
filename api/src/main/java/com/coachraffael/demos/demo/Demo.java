package com.coachraffael.demos.demo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Ein Eintrag im Hub – verwaltete Demo oder externer Katalogeintrag. */
public record Demo(
        long id,
        String slug,
        String kind,
        String sourceRepo,
        String sourcePath,
        String externalUrl,
        Map<String, String> title,
        Map<String, String> teaser,
        Map<String, String> description,
        String category,
        List<String> tags,
        String visibility,
        int sortOrder,
        boolean featured,
        String screenshotUrl,
        String lifecycle,
        Integer ttlDays,
        OffsetDateTime expiresAt,
        String routing,
        int idleMinutes,
        String wakePolicy,
        boolean autoDeploy,
        String state,
        OffsetDateTime stateAt,
        OffsetDateTime lastWokenAt,
        Long currentVersionId,
        String url,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public boolean managed() {
        return "managed".equals(kind);
    }
}
