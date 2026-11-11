package com.coachraffael.demos.catalog;

import com.coachraffael.demos.common.HubProperties;
import com.coachraffael.demos.github.GithubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Stößt {@code screenshot-externals.yml} an: Playwright fotografiert externe Einträge (eigene Domain, z. B.
 * realestate.coach-raffael.com) und meldet das PNG an {@code POST /ci/externals/{slug}/screenshot}.
 * Wie {@link CatalogNotifier}: erst nach dem Commit, im Hintergrund – ein GitHub-Ausfall darf keine
 * Katalogänderung verhindern.
 */
@Component
public class ScreenshotRequester {

    public static final String WORKFLOW = "screenshot-externals.yml";
    private static final Logger log = LoggerFactory.getLogger(ScreenshotRequester.class);

    private final GithubClient github;
    private final HubProperties props;

    public ScreenshotRequester(GithubClient github, HubProperties props) {
        this.github = github;
        this.props = props;
    }

    /** {@code slug} = ein Eintrag; {@code null} = alle externen Einträge. */
    public void request(String slug) {
        String repo = props.github() == null ? null : props.github().hubRepo();
        String token = props.github() == null ? null : props.github().token();
        if (repo == null || repo.isBlank() || token == null || token.isBlank()) {
            log.debug("Screenshot für {} nicht angefordert: GitHub-Anbindung nicht konfiguriert", slug);
            return;
        }
        Runnable send = () -> Thread.ofVirtual().start(() -> {
            try {
                github.dispatchWorkflow(repo, WORKFLOW, "main", Map.of("slug", slug == null ? "" : slug));
                log.info("Screenshot angefordert ({})", slug == null ? "alle externen" : slug);
            } catch (RuntimeException e) {
                log.warn("Screenshot für {} nicht angefordert: {}", slug, e.getMessage());
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
