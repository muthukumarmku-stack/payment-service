package com.learning.paymentservice.payment.web;

import com.learning.paymentservice.common.web.PageResponse;
import com.learning.paymentservice.payment.domain.PaymentMethod;
import com.learning.paymentservice.payment.domain.PaymentStatus;
import com.learning.paymentservice.payment.service.PaymentService;
import com.learning.paymentservice.payment.web.dto.CreatePaymentRequest;
import com.learning.paymentservice.payment.web.dto.PaymentResponse;
import com.learning.paymentservice.payment.web.dto.PaymentSearchCriteria;
import com.learning.paymentservice.payment.web.dto.StatusHistoryResponse;
import com.learning.paymentservice.payment.web.dto.UpdatePaymentStatusRequest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests only the HTTP layer: routing, JSON, status codes, validation and parameter binding. */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    private static final UUID PAYMENT_ID = UUID.fromString("4f1c2b8e-3d6a-4c1e-9a7b-2e5d8f0a1b3c");
    private static final Instant CREATED_AT = Instant.parse("2026-11-13T10:15:30Z");

    private static final String VALID_CREATE_BODY = """
            {
              "reference": "PAY-100",
              "amount": 500.00,
              "currency": "INR",
              "method": "UPI",
              "payerId": "payer-1",
              "payeeId": "payee-1",
              "description": "Order 100"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    private static PaymentResponse paymentResponse(PaymentStatus status) {
        return new PaymentResponse(PAYMENT_ID, "PAY-100", new BigDecimal("500.00"), "INR", PaymentMethod.UPI,
                status, "payer-1", "payee-1", "Order 100", CREATED_AT, CREATED_AT);
    }

    @Nested
    class CreatePayment {

        @Test
        void returns201WithLocationHeaderAndBody() throws Exception {
            given(paymentService.createPayment(any(), isNull())).willReturn(paymentResponse(PaymentStatus.PENDING));

            mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "http://localhost/api/v1/payments/" + PAYMENT_ID))
                    .andExpect(jsonPath("$.id").value(PAYMENT_ID.toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.amount").value(500.00))
                    .andExpect(jsonPath("$.currency").value("INR"))
                    .andExpect(jsonPath("$.createdAt").value("2026-11-13T10:15:30Z"));
        }

        @Test
        void passesTheParsedBodyToTheService() throws Exception {
            given(paymentService.createPayment(any(), isNull())).willReturn(paymentResponse(PaymentStatus.PENDING));

            mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE_BODY));

            ArgumentCaptor<CreatePaymentRequest> captor = ArgumentCaptor.forClass(CreatePaymentRequest.class);
            then(paymentService).should().createPayment(captor.capture(), isNull());
            assertThat(captor.getValue()).isEqualTo(new CreatePaymentRequest("PAY-100", new BigDecimal("500.00"),
                    "INR", PaymentMethod.UPI, "payer-1", "payee-1", "Order 100"));
        }

        @Test
        void forwardsTheIdempotencyKeyHeader() throws Exception {
            given(paymentService.createPayment(any(), eq("key-123"))).willReturn(paymentResponse(PaymentStatus.PENDING));

            mockMvc.perform(post("/api/v1/payments")
                            .header("Idempotency-Key", "key-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_BODY))
                    .andExpect(status().isCreated());

            then(paymentService).should().createPayment(any(), eq("key-123"));
        }

        @Test
        void invalidBodyReturns400AndNeverReachesTheService() throws Exception {
            mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("""
                            { "reference": "", "amount": -1, "currency": "inr", "method": "UPI",
                              "payerId": "payer-1", "payeeId": "payee-1" }
                            """))
                    .andExpect(status().isBadRequest());

            then(paymentService).should(never()).createPayment(any(), any());
        }

        @Test
        void unknownPaymentMethodReturns400() throws Exception {
            mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CREATE_BODY.replace("\"UPI\"", "\"CHEQUE\"")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void malformedJsonReturns400() throws Exception {
            mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void nonJsonContentTypeReturns415() throws Exception {
            mockMvc.perform(post("/api/v1/payments").contentType(MediaType.TEXT_PLAIN).content("hello"))
                    .andExpect(status().isUnsupportedMediaType());
        }
    }

    @Nested
    class GetPayment {

        @Test
        void returnsThePayment() throws Exception {
            given(paymentService.getPayment(PAYMENT_ID)).willReturn(paymentResponse(PaymentStatus.PENDING));

            mockMvc.perform(get("/api/v1/payments/{id}", PAYMENT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(PAYMENT_ID.toString()))
                    .andExpect(jsonPath("$.reference").value("PAY-100"))
                    .andExpect(jsonPath("$.method").value("UPI"));
        }

        @Test
        void idThatIsNotAUuidReturns400() throws Exception {
            mockMvc.perform(get("/api/v1/payments/{id}", "not-a-uuid"))
                    .andExpect(status().isBadRequest());

            then(paymentService).shouldHaveNoInteractions();
        }
    }

    @Nested
    class UpdateStatus {

        @Test
        void returnsTheUpdatedPayment() throws Exception {
            var request = new UpdatePaymentStatusRequest(PaymentStatus.PROCESSING, "Sent to gateway");
            given(paymentService.updateStatus(PAYMENT_ID, request)).willReturn(paymentResponse(PaymentStatus.PROCESSING));

            mockMvc.perform(patch("/api/v1/payments/{id}/status", PAYMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "status": "PROCESSING", "reason": "Sent to gateway" }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PROCESSING"));
        }

        @Test
        void missingStatusReturns400() throws Exception {
            mockMvc.perform(patch("/api/v1/payments/{id}/status", PAYMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"reason\": \"no status\" }"))
                    .andExpect(status().isBadRequest());

            then(paymentService).shouldHaveNoInteractions();
        }

        @Test
        void unknownStatusValueReturns400() throws Exception {
            mockMvc.perform(patch("/api/v1/payments/{id}/status", PAYMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"status\": \"PAID\" }"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class StatusHistory {

        @Test
        void returnsTheEntriesAsAJsonArray() throws Exception {
            given(paymentService.getStatusHistory(PAYMENT_ID)).willReturn(List.of(
                    new StatusHistoryResponse(null, PaymentStatus.PENDING, "Payment created", "api", CREATED_AT),
                    new StatusHistoryResponse(PaymentStatus.PENDING, PaymentStatus.PROCESSING, null, "api", CREATED_AT)));

            mockMvc.perform(get("/api/v1/payments/{id}/history", PAYMENT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].fromStatus").doesNotExist())
                    .andExpect(jsonPath("$[0].toStatus").value("PENDING"))
                    .andExpect(jsonPath("$[1].fromStatus").value("PENDING"))
                    .andExpect(jsonPath("$[1].toStatus").value("PROCESSING"));
        }
    }

    @Nested
    class SearchPayments {

        private final ArgumentCaptor<PaymentSearchCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(PaymentSearchCriteria.class);
        private final ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        private void serviceReturnsOnePayment() {
            given(paymentService.searchPayments(any(), any())).willReturn(
                    new PageResponse<>(List.of(paymentResponse(PaymentStatus.COMPLETED)), 0, 20, 1, 1));
        }

        @Test
        void bindsFiltersAndPagingFromTheQueryString() throws Exception {
            serviceReturnsOnePayment();

            mockMvc.perform(get("/api/v1/payments")
                            .param("status", "COMPLETED")
                            .param("payerId", "payer-1")
                            .param("from", "2026-11-01T00:00:00Z")
                            .param("to", "2026-12-01T00:00:00Z")
                            .param("page", "2")
                            .param("size", "5")
                            .param("sort", "amount,asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                    .andExpect(jsonPath("$.totalElements").value(1));

            then(paymentService).should().searchPayments(criteriaCaptor.capture(), pageableCaptor.capture());
            assertThat(criteriaCaptor.getValue()).isEqualTo(new PaymentSearchCriteria(PaymentStatus.COMPLETED,
                    "payer-1", Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z")));
            assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
            assertThat(pageableCaptor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "amount"));
        }

        @Test
        void withNoParametersUsesEmptyCriteriaAndDefaultPageSize() throws Exception {
            serviceReturnsOnePayment();

            mockMvc.perform(get("/api/v1/payments")).andExpect(status().isOk());

            then(paymentService).should().searchPayments(criteriaCaptor.capture(), pageableCaptor.capture());
            assertThat(criteriaCaptor.getValue()).isEqualTo(new PaymentSearchCriteria(null, null, null, null));
            assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
            assertThat(pageableCaptor.getValue().getSort().isUnsorted()).isTrue();
        }

        @Test
        void capsThePageSizeAt100() throws Exception {
            serviceReturnsOnePayment();

            mockMvc.perform(get("/api/v1/payments").param("size", "5000")).andExpect(status().isOk());

            then(paymentService).should().searchPayments(any(), pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
        }

        @Test
        void reversedDateRangeReturns400() throws Exception {
            mockMvc.perform(get("/api/v1/payments")
                            .param("from", "2026-12-01T00:00:00Z")
                            .param("to", "2026-11-01T00:00:00Z"))
                    .andExpect(status().isBadRequest());

            then(paymentService).shouldHaveNoInteractions();
        }

        @Test
        void unknownStatusFilterReturns400() throws Exception {
            mockMvc.perform(get("/api/v1/payments").param("status", "PAID"))
                    .andExpect(status().isBadRequest());
        }
    }
}
