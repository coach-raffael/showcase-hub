package com.coachraffael.demos.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Lebenszeichen für Traefik-Healthcheck und Smoke-Test – prüft auch die Datenbank. */
@RestController
public class HealthController {

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Integer one = jdbc.sql("select 1").query(Integer.class).single();
        return Map.of("status", one == 1 ? "ok" : "degraded");
    }
}
