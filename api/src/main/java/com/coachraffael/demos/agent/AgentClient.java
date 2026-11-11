package com.coachraffael.demos.agent;

import java.util.List;
import java.util.Map;

/**
 * Der demo-agent auf dem VPS: einziger Weg, Container einer Demo zu starten oder zu stoppen.
 * Er kennt nur dieses feste Befehlsset und nur Demos unter {@code /opt/demos/<slug>/}.
 */
public interface AgentClient {

    enum Action { start, stop, restart, reset }

    /** Zustand einer Demo laut Docker. */
    record DemoStatus(String slug, String state, List<Container> containers) {
    }

    /**
     * @param state {@code running}, {@code exited}, {@code restarting}, …
     * @param health {@code healthy}, {@code unhealthy}, {@code starting} oder leer
     */
    record Container(String service, String state, String health, String memory, String cpu) {
    }

    boolean configured();

    Map<String, Object> run(String slug, Action action);

    DemoStatus status(String slug);

    /** Zustand aller Demos, die der Agent kennt (für den Abgleich im Hub). */
    List<DemoStatus> statusAll();

    String logs(String slug, int tail);
}
