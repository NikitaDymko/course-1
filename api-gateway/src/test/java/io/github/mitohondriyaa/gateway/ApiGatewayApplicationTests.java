package io.github.mitohondriyaa.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Тесты шлюза. Внешние зависимости (Keycloak и три сервиса) подменяются
 * WireMock-заглушками, поэтому тесты не требуют запущенной инфраструктуры.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTests {
    static final String REALM = "microservices-realm";
    static WireMockServer authServer;
    static WireMockServer productServer;
    static WireMockServer orderServer;
    static WireMockServer inventoryServer;
    static RSAKey rsaKey;
    static String token;

    WebTestClient webTestClient;

    @BeforeAll
    static void startStubs() throws Exception {
        authServer = new WireMockServer(options().dynamicPort());
        productServer = new WireMockServer(options().dynamicPort());
        orderServer = new WireMockServer(options().dynamicPort());
        inventoryServer = new WireMockServer(options().dynamicPort());
        authServer.start();
        productServer.start();
        orderServer.start();
        inventoryServer.start();

        // Ключ для подписи тестового JWT и публикация JWKS в заглушке Keycloak.
        rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        String jwks = new JWKSet(rsaKey.toPublicJWK()).toString();

        // Документ обнаружения OIDC: Spring Security запрашивает его при старте,
        // чтобы найти jwks_uri.
        String issuer = "http://localhost:" + authServer.port() + "/realms/" + REALM;
        authServer.stubFor(get(urlEqualTo("/realms/" + REALM + "/.well-known/openid-configuration"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"issuer\":\"" + issuer + "\","
                    + "\"jwks_uri\":\"" + issuer + "/protocol/openid-connect/certs\"}")));

        authServer.stubFor(get(urlEqualTo("/realms/" + REALM + "/protocol/openid-connect/certs"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(jwks)));

        // Заглушки сервисов: любой запрос отвечает 200 с простым телом.
        for (WireMockServer server : List.of(productServer, orderServer, inventoryServer)) {
            server.stubFor(WireMock.any(WireMock.anyUrl())
                .willReturn(aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"ok\"}")));
        }

        token = issueToken();
    }

    private static String issueToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test_user")
            .issuer("http://localhost:" + authServer.port() + "/realms/" + REALM)
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(600)))
            .claim("realm_access", Map.of("roles",
                List.of("SELLER", "MANAGER", "INVENTORY_MANAGER", "STOREKEEPER")))
            .build();

        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
            claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
            () -> "http://localhost:" + authServer.port() + "/realms/" + REALM);
        registry.add("product.url", () -> "http://localhost:" + productServer.port());
        registry.add("order.url", () -> "http://localhost:" + orderServer.port());
        registry.add("inventory.url", () -> "http://localhost:" + inventoryServer.port());
    }

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .build();
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    Integer port;

    @Test
    void shouldProxyToProductService() {
        webTestClient.get()
            .uri("/api/product")
            .header("Authorization", "Bearer " + token)
            .exchange()
            .expectStatus().isOk();

        productServer.verify(WireMock.getRequestedFor(urlEqualTo("/api/product")));
    }

    @Test
    void shouldProxyToOrderService() {
        webTestClient.get()
            .uri("/api/order/my")
            .header("Authorization", "Bearer " + token)
            .exchange()
            .expectStatus().isOk();

        orderServer.verify(WireMock.getRequestedFor(urlEqualTo("/api/order/my")));
    }

    @Test
    void shouldProxyToInventoryService() {
        webTestClient.get()
            .uri("/api/inventory")
            .header("Authorization", "Bearer " + token)
            .exchange()
            .expectStatus().isOk();

        inventoryServer.verify(WireMock.getRequestedFor(urlEqualTo("/api/inventory")));
    }

    @AfterAll
    static void stopStubs() {
        authServer.stop();
        productServer.stop();
        orderServer.stop();
        inventoryServer.stop();
    }
}
