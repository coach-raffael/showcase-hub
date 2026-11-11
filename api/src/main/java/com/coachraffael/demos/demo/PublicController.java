package com.coachraffael.demos.demo;

import com.coachraffael.demos.agent.LifecycleService;
import com.coachraffael.demos.common.Errors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Für die Schlafseite unter demos.coach-raffael.com/&lt;slug&gt; – ohne Anmeldung, nur nicht-private Demos. */
@RestController
public class PublicController {

    public record PublicDemo(String slug, Map<String, String> title, Map<String, String> teaser, String state,
                             String wakePolicy, String url) {
    }

    private final DemoService demos;
    private final LifecycleService lifecycle;

    public PublicController(DemoService demos, LifecycleService lifecycle) {
        this.demos = demos;
        this.lifecycle = lifecycle;
    }

    @GetMapping("/public/demos/{slug}")
    public PublicDemo get(@PathVariable String slug) {
        Demo d = demos.find(slug).filter(x -> x.managed() && !"private".equals(x.visibility()))
                .orElseThrow(() -> Errors.notFound("Demo", slug));
        String state = switch (d.state()) {
            case "running" -> "running";
            case "stopped" -> "sleeping";
            case "deploying" -> "deploying";
            default -> "unavailable";
        };
        return new PublicDemo(d.slug(), d.title(), d.teaser(), state, d.wakePolicy(), d.url());
    }

    public record WakeRequest(String token) {
    }

    @PostMapping("/public/demos/{slug}/wake")
    public Map<String, String> wake(@PathVariable String slug, @RequestBody(required = false) WakeRequest r) {
        LifecycleService.WakeResult result = lifecycle.wake(slug, r == null ? null : r.token());
        return Map.of("state", result.name());
    }
}
