package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loopers.application.payment.RequestOrderPaymentFacade;
import com.loopers.application.payment.ReconcileOrderPaymentFacade;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@SpringBootTest
class OrderPaymentApiIntegrationTest {
    private static final LocalPgServer PG = LocalPgServer.start();
    @Autowired private MockMvc mvc;
    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private ReconcileOrderPaymentFacade reconcilePayment;
    @Autowired private OrderPaymentFixture paymentFixture;
    @Autowired private DatabaseCleanUp cleanUp;

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry registry) {
        registry.add("payment-provider.base-url", PG::baseUrl);
    }

    @BeforeEach
    void resetPg() {
        PG.reset();
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"paymentAttemptKey\":\"not-uuid\"}",
            "{\"paymentAttemptKey\":\"017f22e2-79b0-4cc3-98c4-dc0c0c07398f\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}",
            "{\"paymentAttemptKey\":\"017f22e2-79b0-7cc3-98c4-dc0c0c07398f\",\"cardType\":\"REAL\",\"cardNo\":\"0000-0000-0000-0000\"}"
    })
    void 잘못된_결제_입력은_PG에_보내기_전에_거절한다(String invalidPaymentBody) throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();

        // act
        var response = mvc.perform(post("/api/v1/orders/{id}/confirm", unpaidOrder.orderId())
                .header("X-USER-ID", unpaidOrder.userId()).contentType(MediaType.APPLICATION_JSON)
                .content(invalidPaymentBody));

        // assert
        response.andExpect(status().isBadRequest());
        assertThat(PG.createRequestCount()).isZero();
    }

    @Test
    void PG_조회가_불가능하면_reconcile_API는_저장된_상태와_확인_불가를_202로_반환한다() throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentFixture.createPaymentRequestCommand());
        PG.failPaymentLookups();

        // act
        var response = mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", unpaidOrder.orderId())
                .header("X-USER-ID", unpaidOrder.userId()));

        // assert
        response.andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.lookupAvailable").value(false))
                .andExpect(jsonPath("$.data.orderLookupPath").value("/api/v1/orders/" + unpaidOrder.orderId()));
    }

    @Test
    void 이미_성공한_같은_결제를_확정_API로_다시_요청하면_저장된_성공을_200으로_반환한다() throws Exception {
        // arrange
        var unpaidOrder = paymentFixture.createUnpaidOrder();
        var paymentCommand = paymentFixture.createPaymentRequestCommand();
        var acceptedPayment = requestPayment.request(unpaidOrder.userId(), unpaidOrder.orderId(), paymentCommand);
        PG.succeedPaymentForOrder(acceptedPayment.providerOrderId());
        reconcilePayment.reconcile(unpaidOrder.userId(), unpaidOrder.orderId());
        String samePaymentBody = """
                {"paymentAttemptKey":"%s","cardType":"TEST","cardNo":"0000-0000-0000-0000"}
                """.formatted(paymentCommand.paymentAttemptKey());

        // act
        var response = mvc.perform(post("/api/v1/orders/{id}/confirm", unpaidOrder.orderId())
                .header("X-USER-ID", unpaidOrder.userId()).contentType(MediaType.APPLICATION_JSON).content(samePaymentBody));

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"));
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }
}
