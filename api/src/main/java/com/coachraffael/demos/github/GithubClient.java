package com.coachraffael.demos.github;

import java.util.Map;

/** Was der Hub von GitHub braucht – als Schnittstelle, damit Tests ohne Netz laufen. */
public interface GithubClient {

    /** Inhalt einer Datei (Text) aus einem Repo; {@code ref} = Branch oder Commit. */
    String fetchFile(String repo, String path, String ref);

    /** Startet einen Workflow per {@code workflow_dispatch}. */
    void dispatchWorkflow(String repo, String workflowFile, String ref, Map<String, String> inputs);

    /** Löst {@code repository_dispatch} aus (z. B. Homepage neu bauen). */
    void repositoryDispatch(String repo, String eventType, Map<String, Object> payload);
}
