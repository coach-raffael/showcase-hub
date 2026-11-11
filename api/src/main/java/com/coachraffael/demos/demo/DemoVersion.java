package com.coachraffael.demos.demo;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.OffsetDateTime;

/** Gebaute Version mit Gate-Ergebnis (Build: Code + Sicherheit; Erlebnis: gegen die laufende Demo). */
public record DemoVersion(long id, long demoId, String gitSha, @JsonRawValue String images, @JsonRawValue String gate,
                          boolean gatePassed, @JsonRawValue String gateExperience, Boolean experiencePassed,
                          String runUrl, OffsetDateTime createdAt) {

    /** Freigabefähig für die Öffentlichkeit: Build-Gate grün und Erlebnis-Prüfung grün. */
    public boolean publishable() {
        return gatePassed && Boolean.TRUE.equals(experiencePassed);
    }
}
