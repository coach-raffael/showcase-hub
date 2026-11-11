package com.coachraffael.demos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Öffentlich: Health, Katalog, Schlafseite. CI-Token nur für /ci. Alles andere: Rolle demos-admin. */
@EnabledIf("com.coachraffael.demos.DatabaseTest#available")
@SpringBootTest
@AutoConfigureMockMvc
class SecurityTest extends DatabaseTest {

    static final String CI = "Bearer hubci_test-token-0123456789";

    @Autowired
    MockMvc mvc;

    @Test
    void publicEndpoints() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/catalog")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/catalog/langtutor")).andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://langtutor.cloud"));
        mvc.perform(get("/public/demos/does-not-exist")).andExpect(status().isNotFound());
        mvc.perform(post("/public/demos/does-not-exist/wake")).andExpect(status().isNotFound());
    }

    @Test
    void adminNeedsRole() throws Exception {
        mvc.perform(get("/demos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/demos").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_learner"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/demos").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_demos-admin"))))
                .andExpect(status().isOk());
        mvc.perform(get("/mcp-policy").header("Authorization", CI)).andExpect(status().isForbidden());
    }

    @Test
    void ciTokenOnlyForCi() throws Exception {
        mvc.perform(get("/ci/deploy/nope/1/bundle")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ci/deploy/nope/1/bundle").header("Authorization", "Bearer hubci_wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ci/deploy/nope/1/bundle").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_demos-admin"))))
                .andExpect(status().isForbidden());
        // Token passt – die Demo gibt es nicht
        mvc.perform(get("/ci/deploy/nope/1/bundle").header("Authorization", CI)).andExpect(status().isNotFound());
    }
}
