package com.capitec.connect.fraudengine.integration;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.integration.util.IntegrationTestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real JWT resource server path against a real Keycloak.
 *
 * <p>The other security tests use Spring Security's mock JWT, which bypasses token validation
 * entirely. This test proves that a token issued by the realm in {@code config/keycloak} is
 * accepted, which depends on the issuer in the token matching the issuer the service is
 * configured with. Keycloak issues the issuer it was started with, so the container is given a
 * fixed host port and the issuer is registered from that same URL.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KeycloakSecurityIT {

    private static final int KEYCLOAK_HOST_PORT = findFreePort();
    private static final String REALM = "fraud-engine";
    private static final String KEYCLOAK_URL = "http://localhost:" + KEYCLOAK_HOST_PORT;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> keycloak = keycloakContainer();

    private static GenericContainer<?> keycloakContainer() {
        GenericContainer<?> container = new GenericContainer<>(
                DockerImageName.parse("quay.io/keycloak/keycloak:26.2.5"))
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            // The hostname decides the issuer that Keycloak puts in the token, so it has to be the
            // URL the service is told to trust.
            .withEnv("KC_HOSTNAME", KEYCLOAK_URL)
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withExposedPorts(8080)
            .withFileSystemBind(
                Path.of("config", "keycloak").toAbsolutePath().toString(),
                "/opt/keycloak/data/import",
                BindMode.READ_ONLY)
            // Wait until the imported realm is actually served, not just until the port is open.
            .waitingFor(Wait.forHttp("/realms/" + REALM).forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));
        // Bind the reserved port explicitly, so the URL the service trusts is known up front.
        container.setPortBindings(List.of(KEYCLOAK_HOST_PORT + ":8080"));
        return container;
    }

    @DynamicPropertySource
    static void registerKeycloak(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
            () -> KEYCLOAK_URL + "/realms/" + REALM);
        // The issuer in the token and the keys have to come from the same Keycloak instance.
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            () -> KEYCLOAK_URL + "/realms/" + REALM + "/protocol/openid-connect/certs");
        registry.add("security.enabled", () -> "true");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aTokenFromTheRealmIsAcceptedAndATokenWithoutTheRoleIsNot() {
        String analystToken = requestToken("fraud-analyst", "fraud-analyst-dev");
        assertNotNull(analystToken, "the realm should issue a token for the analyst user");

        TransactionRequestDto request = IntegrationTestDataFactory.cleanTransaction("ACC-KEYCLOAK").build();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(analystToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> accepted = restTemplate.exchange(
            "/api/v1/transactions/evaluate", HttpMethod.POST, new HttpEntity<>(request, headers), String.class);

        assertEquals(HttpStatus.CREATED, accepted.getStatusCode(),
            "the analyst token should be accepted: " + accepted.getBody());
        assertTrue(accepted.getBody().contains("\"isFraudulent\":false"), accepted.getBody());
    }

    @Test
    void aRequestWithoutATokenIsRejected() {
        ResponseEntity<String> rejected = restTemplate.getForEntity("/api/v1/fraud-cases", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatusCode());
    }

    @Test
    void aTokenWithoutTheRequiredRoleIsForbidden() {
        // The viewer user exists in the realm but has no analyst role, so the case list, which
        // requires FRAUD_ANALYST, has to be refused. This is an authorization check, not an
        // audience check.
        String viewerToken = requestToken("fraud-viewer", "fraud-viewer-dev");
        assertNotNull(viewerToken, "the realm should issue a token for the viewer user");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(viewerToken);

        ResponseEntity<String> refused = restTemplate.exchange(
            "/api/v1/fraud-cases", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
    }

    private String requestToken(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", "fraud-engine-client");
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<String> response = restTemplate.exchange(
            KEYCLOAK_URL + "/realms/" + REALM + "/protocol/openid-connect/token",
            HttpMethod.POST, new HttpEntity<>(form, headers), String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            return null;
        }
        return extractAccessToken(response.getBody());
    }

    private String extractAccessToken(String body) {
        if (body == null) {
            return null;
        }
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            return json.hasNonNull("access_token") ? json.get("access_token").asText() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Reserves a host port for Keycloak. The port has to be known before the container starts,
     * because the issuer in the token is derived from the hostname Keycloak was given.
     */
    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not reserve a port for Keycloak", ex);
        }
    }
}
