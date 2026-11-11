package com.coachraffael.demos.catalog;

import com.coachraffael.demos.common.Actor;
import com.coachraffael.demos.common.Errors;
import com.coachraffael.demos.common.HubProperties;
import com.coachraffael.demos.demo.Demo;
import com.coachraffael.demos.demo.DemoService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Screenshots für externe Einträge. Verwaltete Demos bekommen ihren Screenshot nach jedem Deploy
 * (deploy-demo.yml); externe laufen woanders, also fotografiert sie {@code screenshot-externals.yml}
 * – angestoßen beim Anlegen/Ändern, per Knopf im Hub und wöchentlich.
 */
@Service
public class ExternalScreenshotService {

    public record Target(String slug, String url) {
    }

    private final DemoService demos;
    private final ScreenshotStore store;
    private final ScreenshotRequester requester;
    private final HubProperties props;

    public ExternalScreenshotService(DemoService demos, ScreenshotStore store, ScreenshotRequester requester,
                                     HubProperties props) {
        this.demos = demos;
        this.store = store;
        this.requester = requester;
        this.props = props;
    }

    /** Was der Workflow fotografieren soll: ein externer Eintrag oder alle (slug leer). */
    public List<Target> targets(String slug) {
        if (slug != null && !slug.isBlank()) {
            return List.of(target(external(slug)));
        }
        return demos.list().stream().filter(d -> !d.managed()).map(this::target).toList();
    }

    /** PNG speichern und im Katalog verlinken; ?v= wechselt mit jeder Aufnahme (Caches der Homepage). */
    public String save(String slug, byte[] png, Actor actor) {
        Demo d = external(slug);
        store.save(d.slug(), png);
        String url = props.publicBase() + "/api/catalog/" + d.slug() + "/screenshot.png?v="
                + Long.toString(Instant.now().getEpochSecond(), 36);
        demos.updateCatalog(d.slug(), null, null, url, actor);
        return url;
    }

    /** Neue Aufnahme anfordern (Knopf im Hub). */
    public void refresh(String slug) {
        requester.request(external(slug).slug());
    }

    private Demo external(String slug) {
        Demo d = demos.get(slug);
        if (d.managed()) {
            throw new Errors.Conflict(slug + " ist eine verwaltete Demo – ihr Screenshot entsteht beim Ausrollen");
        }
        return d;
    }

    private Target target(Demo d) {
        return new Target(d.slug(), d.url());
    }
}
