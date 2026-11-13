package com.learning.paymentservice.common.exception;

import com.learning.paymentservice.payment.domain.PaymentStatus;
import com.learning.paymentservice.payment.exception.DuplicatePaymentReferenceException;
import com.learning.paymentservice.payment.exception.IdempotencyKeyReuseException;
import com.learning.paymentservice.payment.exception.InvalidStatusTransitionException;
import com.learning.paymentservice.payment.exception.PaymentNotFoundException;
import com.learning.paymentservice.payment.exception.UnsupportedSortPropertyException;
import com.learning.paymentservice.payment.service.PaymentService;
import com.learning.paymentservice.payment.web.PaymentController;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the exact error contract: every failure is {@code application/problem+json}
 * with a stable {@code code}, and nothing internal leaks out.
 */
@WebMvcTest(PaymentController.class)
class ErrorResponseTest {

    private static final UUID PAYMENT_ID = UUID.fromString("4f1c2b8e-3d6a-4c1e-9a7b-2e5d8f0a1b3c");

    private static final String VALID_CREATE_BODY = """
            { "reference": "PAY-1", "amount": 10, "currency": "INR", "method": "UPI",
              "payerId": "payer-1", "payeeId": "payee-1" }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    /** The fields every error response must have. */
    private static ResultActions expectProblem(ResultActions result, int status, String code, String instance)
            throws Exception {
        return result
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title", notNullValue()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    private ResultActions postPayment(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions patchStatus(String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/payments/{id}/status", PAYMENT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Nested
    class BusinessErrors {

        @Test
        void unknownPaymentIs404WithItsId() throws Exception {
            given(paymentService.getPayment(PAYMENT_ID)).willThrow(new PaymentNotFoundException(PAYMENT_ID));

            expectProblem(mockMvc.perform(get("/api/v1/payments/{id}", PAYMENT_ID)),
                    404, "PAYMENT_NOT_FOUND", "/api/v1/payments/" + PAYMENT_ID)
                    .andExpect(jsonPath("$.title").value("Not Found"))
                    .andExpect(jsonPath("$.detail").value("Payment " + PAYMENT_ID + " was not found"))
                    .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()));
        }

        @Test
        void invalidTransitionIs409AndListsTheAllowedStatuses() throws Exception {
            given(paymentService.updateStatus(any(), any())).willThrow(
                    new InvalidStatusTransitionException(PAYMENT_ID, PaymentStatus.PROCESSING, PaymentStatus.REFUNDED));

            expectProblem(patchStatus("{ \"status\": \"REFUNDED\" }"),
                    409, "INVALID_STATUS_TRANSITION", "/api/v1/payments/" + PAYMENT_ID + "/status")
                    .andExpect(jsonPath("$.currentStatus").value("PROCESSING"))
                    .andExpect(jsonPath("$.requestedStatus").value("REFUNDED"))
                    .andExpect(jsonPath("$.allowedStatuses[0]").value("COMPLETED"))
                    .andExpect(jsonPath("$.allowedStatuses[1]").value("FAILED"));
        }

        @Test
        void finalStatusHasNoAllowedStatuses() throws Exception {
            given(paymentService.updateStatus(any(), any())).willThrow(
                    new InvalidStatusTransitionException(PAYMENT_ID, PaymentStatus.CANCELLED, PaymentStatus.PENDING));

            patchStatus("{ \"status\": \"PENDING\" }")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.allowedStatuses").isEmpty());
        }

        @Test
        void duplicateReferenceIs409() throws Exception {
            given(paymentService.createPayment(any(), any())).willThrow(new DuplicatePaymentReferenceException("PAY-1"));

            expectProblem(postPayment(VALID_CREATE_BODY), 409, "DUPLICATE_REFERENCE", "/api/v1/payments")
                    .andExpect(jsonPath("$.reference").value("PAY-1"));
        }

        @Test
        void reusedIdempotencyKeyIs422() throws Exception {
            given(paymentService.createPayment(any(), any())).willThrow(new IdempotencyKeyReuseException("key-1"));

            expectProblem(postPayment(VALID_CREATE_BODY), 422, "IDEMPOTENCY_KEY_REUSED", "/api/v1/payments");
        }

        @Test
        void unsupportedSortIs400AndListsTheSupportedFields() throws Exception {
            given(paymentService.searchPayments(any(), any())).willThrow(
                    new UnsupportedSortPropertyException("payerSecret", Set.of("createdAt", "amount")));

            expectProblem(mockMvc.perform(get("/api/v1/payments").param("sort", "payerSecret")),
                    400, "UNSUPPORTED_SORT_PROPERTY", "/api/v1/payments")
                    .andExpect(jsonPath("$.supportedProperties[0]").value("amount"))
                    .andExpect(jsonPath("$.supportedProperties[1]").value("createdAt"));
        }
    }

    @Nested
    class PersistenceAndUnexpectedErrors {

        @Test
        void concurrentUpdateIs409() throws Exception {
            given(paymentService.updateStatus(any(), any())).willThrow(
                    new ObjectOptimisticLockingFailureException("Payment", PAYMENT_ID));

            expectProblem(patchStatus("{ \"status\": \"PROCESSING\" }"),
                    409, "CONCURRENT_UPDATE", "/api/v1/payments/" + PAYMENT_ID + "/status");
        }

        @Test
        void constraintViolationIs409WithoutLeakingSql() throws Exception {
            given(paymentService.createPayment(any(), any())).willThrow(new DataIntegrityViolationException(
                    "could not execute statement; SQL [insert into payments ...]; constraint [uk_payments_reference]"));

            expectProblem(postPayment(VALID_CREATE_BODY), 409, "DATA_CONFLICT", "/api/v1/payments")
                    .andExpect(jsonPath("$.detail", not(containsString("SQL"))))
                    .andExpect(jsonPath("$.detail", not(containsString("uk_payments"))));
        }

        @Test
        void unexpectedErrorIs500WithAGenericMessage() throws Exception {
            given(paymentService.getPayment(PAYMENT_ID)).willThrow(new IllegalStateException("db password is hunter2"));

            expectProblem(mockMvc.perform(get("/api/v1/payments/{id}", PAYMENT_ID)),
                    500, "INTERNAL_ERROR", "/api/v1/payments/" + PAYMENT_ID)
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred. Please try again later."))
                    .andExpect(jsonPath("$.trace").doesNotExist());
        }
    }

    @Nested
    class RequestErrors {

        @Test
        void invalidBodyListsEveryBadFieldSortedByName() throws Exception {
            expectProblem(postPayment("""
                            { "reference": "", "amount": -1, "currency": "inr", "method": "UPI",
                              "payerId": "payer-1", "payeeId": "payee-1" }
                            """),
                    400, "VALIDATION_FAILED", "/api/v1/payments")
                    .andExpect(jsonPath("$.detail").value("Request validation failed"))
                    .andExpect(jsonPath("$.errors.length()").value(3))
                    .andExpect(jsonPath("$.errors[0].field").value("amount"))
                    .andExpect(jsonPath("$.errors[0].message").value("must be greater than 0"))
                    .andExpect(jsonPath("$.errors[1].field").value("currency"))
                    .andExpect(jsonPath("$.errors[2].field").value("reference"));
        }

        @Test
        void payingYourselfIsAValidationError() throws Exception {
            expectProblem(postPayment(VALID_CREATE_BODY.replace("payee-1", "payer-1")),
                    400, "VALIDATION_FAILED", "/api/v1/payments")
                    .andExpect(jsonPath("$.errors[0].field").value("payeeDifferentFromPayer"))
                    .andExpect(jsonPath("$.errors[0].message").value("payeeId must be different from payerId"));
        }

        @Test
        void unknownEnumValueNamesTheFieldAndAllowedValues() throws Exception {
            expectProblem(postPayment(VALID_CREATE_BODY.replace("\"UPI\"", "\"CHEQUE\"")),
                    400, "MALFORMED_REQUEST", "/api/v1/payments")
                    .andExpect(jsonPath("$.detail").value(
                            "Invalid value 'CHEQUE' for 'method'. Allowed values: [CARD, BANK_TRANSFER, UPI, WALLET]."));
        }

        @Test
        void malformedJsonGivesAGenericMessage() throws Exception {
            expectProblem(postPayment("{ not json"), 400, "MALFORMED_REQUEST", "/api/v1/payments")
                    .andExpect(jsonPath("$.detail").value("The request body could not be read. Check that it is valid JSON."));
        }

        @Test
        void idThatIsNotAUuidNamesTheParameter() throws Exception {
            expectProblem(mockMvc.perform(get("/api/v1/payments/{id}", "abc")),
                    400, "MALFORMED_REQUEST", "/api/v1/payments/abc")
                    .andExpect(jsonPath("$.detail").value("Invalid value 'abc' for 'paymentId'."));
        }

        @Test
        void tooLongIdempotencyKeyIsAValidationError() throws Exception {
            expectProblem(mockMvc.perform(post("/api/v1/payments")
                            .header("Idempotency-Key", "k".repeat(101))
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE_BODY)),
                    400, "VALIDATION_FAILED", "/api/v1/payments")
                    .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        }

        @Test
        void reversedDateRangeIsAValidationError() throws Exception {
            expectProblem(mockMvc.perform(get("/api/v1/payments")
                            .param("from", "2026-12-01T00:00:00Z").param("to", "2026-11-01T00:00:00Z")),
                    400, "VALIDATION_FAILED", "/api/v1/payments")
                    .andExpect(jsonPath("$.errors[0].field").value("dateRangeValid"))
                    .andExpect(jsonPath("$.errors[0].message").value("'from' must be before 'to'"));
        }

        @Test
        void unknownStatusFilterNamesTheBadValue() throws Exception {
            expectProblem(mockMvc.perform(get("/api/v1/payments").param("status", "PAID")),
                    400, "VALIDATION_FAILED", "/api/v1/payments")
                    .andExpect(jsonPath("$.errors[0].field").value("status"))
                    .andExpect(jsonPath("$.errors[0].message").value("has an invalid value 'PAID'"));
        }
    }

    @Nested
    class SpringMvcErrors {

        @Test
        void wrongContentTypeIs415() throws Exception {
            expectProblem(mockMvc.perform(post("/api/v1/payments").contentType(MediaType.TEXT_PLAIN).content("x")),
                    415, "UNSUPPORTED_MEDIA_TYPE", "/api/v1/payments");
        }

        @Test
        void unsupportedMethodIs405() throws Exception {
            expectProblem(mockMvc.perform(delete("/api/v1/payments/{id}", PAYMENT_ID)),
                    405, "METHOD_NOT_ALLOWED", "/api/v1/payments/" + PAYMENT_ID);
        }

        @Test
        void unknownUrlIs404() throws Exception {
            expectProblem(mockMvc.perform(get("/api/v1/nothing-here")), 404, "NOT_FOUND", "/api/v1/nothing-here");
        }
    }

    @Test
    void everyErrorCategoryMapsToAStatus() {
        for (ErrorCategory category : ErrorCategory.values()) {
            org.assertj.core.api.Assertions.assertThat(GlobalExceptionHandler.toHttpStatus(category)).isNotNull();
        }
        org.assertj.core.api.Assertions.assertThat(List.of(ErrorCategory.values())).hasSize(4);
    }
}
