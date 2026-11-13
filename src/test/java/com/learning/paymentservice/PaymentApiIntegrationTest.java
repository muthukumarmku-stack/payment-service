package com.learning.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application over real HTTP against a real PostgreSQL (Testcontainers),
 * with Flyway, JPA, transactions, validation and error handling all wired as in
 * production. Each test uses its own payer id, so tests never see each other's data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PaymentApiIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private RestClient client;
    private String payerId;

    @BeforeEach
    void setUp() {
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {
                    // Never throw: the tests assert on 4xx/5xx responses directly.
                })
                .build();
        payerId = "payer-" + UUID.randomUUID();
    }

    private Map<String, Object> createBody(String reference) {
        return Map.of(
                "reference", reference,
                "amount", "1500.50",
                "currency", "INR",
                "method", "UPI",
                "payerId", payerId,
                "payeeId", "shop-1",
                "description", "Integration test");
    }

    private ResponseEntity<JsonNode> create(String reference, String idempotencyKey) {
        var request = client.post().uri("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createBody(reference));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return request.retrieve().toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> changeStatus(String id, String status) {
        return client.patch().uri("/api/v1/payments/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", status, "reason", "moving to " + status))
                .retrieve().toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String uri, Object... variables) {
        return client.get().uri(uri, variables).retrieve().toEntity(JsonNode.class);
    }

    private static String reference() {
        return "IT-" + UUID.randomUUID();
    }

    @Test
    void fullLifecycleFromCreationToRefund() {
        ResponseEntity<JsonNode> created = create(reference(), null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = created.getBody().get("id").asText();
        assertThat(created.getHeaders().getLocation()).hasToString("http://localhost:" + port + "/api/v1/payments/" + id);
        assertThat(created.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(created.getBody().get("amount").decimalValue()).isEqualByComparingTo("1500.50");
        assertThat(created.getBody().get("createdAt").isNull()).isFalse();

        assertThat(changeStatus(id, "PROCESSING").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changeStatus(id, "COMPLETED").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> refunded = changeStatus(id, "REFUNDED");
        assertThat(refunded.getBody().get("status").asText()).isEqualTo("REFUNDED");

        JsonNode fetched = get("/api/v1/payments/{id}", id).getBody();
        assertThat(fetched.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(fetched.get("payerId").asText()).isEqualTo(payerId);

        JsonNode history = get("/api/v1/payments/{id}/history", id).getBody();
        assertThat(history).hasSize(4);
        assertThat(history.get(0).get("fromStatus").isNull()).isTrue();
        assertThat(history.get(0).get("toStatus").asText()).isEqualTo("PENDING");
        assertThat(history.get(3).get("fromStatus").asText()).isEqualTo("COMPLETED");
        assertThat(history.get(3).get("toStatus").asText()).isEqualTo("REFUNDED");
        assertThat(history.get(3).get("reason").asText()).isEqualTo("moving to REFUNDED");
    }

    @Test
    void invalidTransitionIsRejectedAndLeavesNoTrace() {
        String id = create(reference(), null).getBody().get("id").asText();

        ResponseEntity<JsonNode> response = changeStatus(id, "COMPLETED"); // PENDING -> COMPLETED skips PROCESSING

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_STATUS_TRANSITION");
        assertThat(get("/api/v1/payments/{id}", id).getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(get("/api/v1/payments/{id}/history", id).getBody()).hasSize(1);
    }

    @Test
    void retryWithTheSameIdempotencyKeyCreatesOnlyOnePayment() {
        String reference = reference();
        String key = "key-" + UUID.randomUUID();

        ResponseEntity<JsonNode> first = create(reference, key);
        ResponseEntity<JsonNode> retry = create(reference, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(get("/api/v1/payments?payerId={payer}", payerId).getBody().get("totalElements").asLong())
                .isEqualTo(1);
    }

    @Test
    void duplicateReferenceWithoutAKeyIsAConflict() {
        String reference = reference();
        create(reference, null);

        ResponseEntity<JsonNode> second = create(reference, null);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code").asText()).isEqualTo("DUPLICATE_REFERENCE");
    }

    @Test
    void searchFiltersByPayerAndStatusAndPagesNewestFirst() {
        String first = create(reference(), null).getBody().get("id").asText();
        String second = create(reference(), null).getBody().get("id").asText();
        String third = create(reference(), null).getBody().get("id").asText();
        changeStatus(second, "PROCESSING");

        JsonNode pending = get("/api/v1/payments?payerId={p}&status=PENDING", payerId).getBody();
        assertThat(pending.get("totalElements").asLong()).isEqualTo(2);

        JsonNode firstPage = get("/api/v1/payments?payerId={p}&size=2", payerId).getBody();
        assertThat(firstPage.get("totalElements").asLong()).isEqualTo(3);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPage.get("content").get(0).get("id").asText()).isEqualTo(third);

        JsonNode lastPage = get("/api/v1/payments?payerId={p}&size=2&page=1", payerId).getBody();
        assertThat(lastPage.get("content")).hasSize(1);
        assertThat(lastPage.get("content").get(0).get("id").asText()).isEqualTo(first);
    }

    @Test
    void unknownPaymentIs404ForDetailsAndHistory() {
        UUID unknown = UUID.randomUUID();

        assertThat(get("/api/v1/payments/{id}", unknown).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> history = get("/api/v1/payments/{id}/history", unknown);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(history.getBody().get("code").asText()).isEqualTo("PAYMENT_NOT_FOUND");
    }

    @Test
    void invalidRequestIs400WithFieldErrors() {
        ResponseEntity<JsonNode> response = client.post().uri("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reference", "IT-bad", "amount", "0", "currency", "XYZ",
                        "method", "UPI", "payerId", payerId, "payeeId", "shop-1"))
                .retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().get("errors").findValuesAsText("field")).containsExactly("amount", "currency");
    }

    @Test
    void healthAndApiDocsAreAvailable() {
        assertThat(get("/actuator/health").getBody().get("status").asText()).isEqualTo("UP");

        ResponseEntity<JsonNode> docs = get("/v3/api-docs");
        assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(docs.getBody().get("info").get("title").asText()).isEqualTo("Payment Management API");
        assertThat(docs.getBody().get("paths").has("/api/v1/payments/{paymentId}/status")).isTrue();

        ResponseEntity<String> ui = client.get().uri("/swagger-ui/index.html")
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE).retrieve().toEntity(String.class);
        assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
