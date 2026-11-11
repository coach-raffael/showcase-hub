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
 * Meldet der Homepage (coach-raffael.com) per {@code repository_dispatch}, dass sich der öffentliche Katalog
 * geändert hat – die Homepage liest den Feed dann beim Neubau. Läuft erst nach dem Commit und im Hintergrund:
 * ein GitHub-Ausfall darf keine Katalogänderung verhindern.
 */
@Component
public class CatalogNotifier {

    public static final String EVENT = "showcase-catalog-changed";
    private static final Logger log = LoggerFactory.getLogger(CatalogNotifier.class);

    private final GithubClient github;
    private final HubProperties props;

    public CatalogNotifier(GithubClient github, HubProperties props) {
        this.github = github;
        this.props = props;
    }

    public void catalogChanged(String reason) {
        String repo = props.github() == null ? null : props.github().homepageRepo();
        String token = props.github() == null ? null : props.github().token();
        if (repo == null || repo.isBlank() || token == null || token.isBlank()) {
            log.debug("Katalog geändert ({}), Homepage-Anbindung nicht konfiguriert", reason);
            return;
        }
        Runnable send = () -> Thread.ofVirtual().start(() -> {
            try {
                github.repositoryDispatch(repo, EVENT, Map.of("reason", reason,
                        "feed", props.publicBase() + "/api/catalog"));
                log.info("Homepage-Neubau angestoßen ({})", reason);
            } catch (RuntimeException e) {
                log.warn("Homepage-Neubau nicht angestoßen ({}): {}", reason, e.getMessage());
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
