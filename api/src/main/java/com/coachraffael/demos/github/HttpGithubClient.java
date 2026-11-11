package com.coachraffael.demos.github;

import com.coachraffael.demos.common.Errors;
import com.coachraffael.demos.common.HubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** GitHub-REST über {@code api.github.com}. Lesen öffentlicher Repos geht auch ohne Token. */
@Component
public class HttpGithubClient implements GithubClient {

    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern FILE = Pattern.compile("^[A-Za-z0-9_./-]+$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9_./-]+$");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final HubProperties props;
    private final ObjectMapper json;

    public HttpGithubClient(HubProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public String fetchFile(String repo, String path, String ref) {
        check(REPO, repo, "Repo");
        check(FILE, path, "Pfad");
        if (path.contains("..")) throw new IllegalArgumentException("Pfad darf kein .. enthalten");
        check(REF, ref, "Ref");
        URI uri = URI.create("https://api.github.com/repos/" + repo + "/contents/" + path + "?ref="
                + URLEncoder.encode(ref, StandardCharsets.UTF_8));
        HttpRequest.Builder b = request(uri).header("Accept", "application/vnd.github.raw+json").GET();
        HttpResponse<String> r = send(b.build());
        if (r.statusCode() == 404) {
            throw new Errors.NotFound(path + " nicht gefunden in " + repo + "@" + ref
                    + " (Repo öffentlich? Datei im Root?)");
        }
        expectOk(r, "Datei lesen");
        if (r.body().length() > 64 * 1024) {
            throw new IllegalArgumentException(path + " ist größer als 64 KB");
        }
        return r.body();
    }

    @Override
    public void dispatchWorkflow(String repo, String workflowFile, String ref, Map<String, String> inputs) {
        requireToken();
        check(REPO, repo, "Repo");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ref", ref);
        body.put("inputs", inputs);
        HttpResponse<String> r = send(request(URI.create("https://api.github.com/repos/" + repo
                + "/actions/workflows/" + workflowFile + "/dispatches"))
                .POST(HttpRequest.BodyPublishers.ofString(write(body))).build());
        expectOk(r, "Workflow starten");
    }

    @Override
    public void repositoryDispatch(String repo, String eventType, Map<String, Object> payload) {
        requireToken();
        check(REPO, repo, "Repo");
        HttpResponse<String> r = send(request(URI.create("https://api.github.com/repos/" + repo + "/dispatches"))
                .POST(HttpRequest.BodyPublishers.ofString(write(Map.of("event_type", eventType,
                        "client_payload", payload)))).build());
        expectOk(r, "repository_dispatch");
    }

    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "showcase-hub");
        String token = props.github() == null ? null : props.github().token();
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token.trim());
        }
        return b;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Errors.Conflict("GitHub nicht erreichbar: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Errors.Conflict("GitHub-Aufruf unterbrochen");
        }
    }

    private static void expectOk(HttpResponse<String> r, String what) {
        if (r.statusCode() / 100 != 2) {
            String body = r.body() == null ? "" : r.body();
            throw new Errors.Conflict("GitHub (" + what + ") antwortet " + r.statusCode() + ": "
                    + body.substring(0, Math.min(200, body.length())));
        }
    }

    private void requireToken() {
        if (props.github() == null || props.github().token() == null || props.github().token().isBlank()) {
            throw new Errors.Conflict("HUB_GITHUB_TOKEN ist nicht gesetzt");
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(Pattern p, String v, String label) {
        if (v == null || !p.matcher(v).matches()) {
            throw new IllegalArgumentException(label + " ungültig: " + v);
        }
    }
}
