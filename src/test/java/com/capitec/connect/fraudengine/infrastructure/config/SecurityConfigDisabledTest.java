package com.capitec.connect.fraudengine.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "security.enabled=false")
class SecurityConfigDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Should allow access to protected endpoints when security is disabled")
    void shouldAllowAccessWhenSecurityDisabled() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}