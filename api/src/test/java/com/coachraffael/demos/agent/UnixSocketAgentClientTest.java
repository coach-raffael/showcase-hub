package com.coachraffael.demos.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnixSocketAgentClientTest {

    /** Gleicher Testvektor wie agent/test_demo_agent.py (dort unabhängig mit openssl berechnet). */
    @Test
    void signatureMatchesAgent() {
        assertThat(UnixSocketAgentClient.sign("secret", "1700000000", "n0nce-0123456789ab", "POST", "/v1/demos/shop/start"))
                .isEqualTo("50fa117d9d80da0432d46d69e066342086bf1d67977750f8d4d83b61da7c50a3");
    }
}
