package com.coachraffael.demos.demo;

import com.coachraffael.demos.manifest.DemoManifest;
import com.coachraffael.demos.manifest.ManifestParser;
import com.coachraffael.demos.manifest.ManifestParserTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Das Gate ist im Hub definiert: fehlende Pflichtprüfungen zählen wie rote. */
class GateTest {

    final ObjectMapper json = new ObjectMapper();

    JsonNode checks(String... namesOk) throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < namesOk.length; i++) {
            String[] p = namesOk[i].split(":");
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(p[0]).append("\",\"ok\":").append(p[1]).append(",\"detail\":\"x\"}");
        }
        return json.readTree(sb.append(']').toString());
    }

    @Test
    void allRequiredGreenPasses() throws Exception {
        var r = VersionService.evaluate(checks("build:true", "tests:true", "lint:true", "secrets:true", "dependencies:true",
                "image-scan:true", "readme:true", "license:true"), VersionService.BUILD_CHECKS);
        assertThat(r.passed()).isTrue();
        assertThat(r.missing()).isEmpty();
    }

    @Test
    void missingCheckFailsTheGate() throws Exception {
        var r = VersionService.evaluate(checks("build:true", "tests:true", "lint:true"), VersionService.BUILD_CHECKS);
        assertThat(r.passed()).isFalse();
        assertThat(r.missing()).contains("secrets", "license", "image-scan");
    }

    @Test
    void redExtraCheckOrDuplicateRedFails() throws Exception {
        var all = "build:true,tests:true,lint:true,secrets:true,dependencies:true,image-scan:true,readme:true,license:true";
        var extraRed = VersionService.evaluate(checks((all + ",custom:false").split(",")), VersionService.BUILD_CHECKS);
        assertThat(extraRed.passed()).isFalse();
        assertThat(extraRed.failed()).containsExactly("custom");
        var dup = VersionService.evaluate(checks((all + ",tests:false,tests:true").split(",")), VersionService.BUILD_CHECKS);
        assertThat(dup.passed()).isFalse();
        assertThat(dup.failed()).containsExactly("tests");
    }

    @Test
    void nonBooleanOkIsRed() throws Exception {
        var r = VersionService.evaluate(json.readTree("[{\"name\":\"smoke\",\"ok\":\"true\"}]"), java.util.Set.of("smoke"));
        assertThat(r.passed()).isFalse();
        assertThat(VersionService.evaluate(null, java.util.Set.of("smoke")).missing()).containsExactly("smoke");
    }

    @Test
    void imagesMustMatchManifestAndCommit() {
        DemoManifest m = new ManifestParser().parse(ManifestParserTest.VALID);
        String sha = "0123456789abcdef0123456789abcdef01234567";
        String web = "ghcr.io/coach-raffael/realestate-platform-web@sha256:" + "a".repeat(64);
        String api = "ghcr.io/coach-raffael/realestate-platform-api:" + sha;
        assertThat(VersionService.checkImages(m, Map.of("web", web, "api", api), sha, true))
                .containsEntry("web", web).containsEntry("api", api);
        assertThatThrownBy(() -> VersionService.checkImages(m, Map.of("web", web), sha, true)).hasMessageContaining("images.api");
        assertThat(VersionService.checkImages(m, Map.of("web", web), sha, false)).containsOnlyKeys("web");
        assertThatThrownBy(() -> VersionService.checkImages(m, Map.of("web", web, "api", "ghcr.io/coach-raffael/realestate-platform-api:latest"), sha, true))
                .hasMessageContaining("images.api");
        assertThatThrownBy(() -> VersionService.checkImages(m, Map.of("web", web, "api", api, "db", "postgres:16"), sha, true))
                .hasMessageContaining("images.db");
    }
}
