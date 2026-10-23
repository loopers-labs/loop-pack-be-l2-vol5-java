package com.loopers.support.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.payment.PaymentStatus;
import com.loopers.infrastructure.payment.HttpPaymentGateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class LocalPgServerTest {
    private LocalPgServer localPgServer;
    private HttpPaymentGateway paymentGateway;

    @BeforeEach
    void setUp() {
        localPgServer = LocalPgServer.start();
        paymentGateway = new HttpPaymentGateway(RestClient.builder(), URI.create(localPgServer.baseUrl()),
                URI.create("http://127.0.0.1:8080/api/v1/payments/callback"),
                Duration.ofMillis(500), Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        localPgServer.releaseResponses();
        paymentGateway.close();
        localPgServer.close();
    }

    @Test
    void PG가_생성_응답을_보류해도_이미_저장한_거래는_동시에_조회할_수_있다() throws Exception {
        // arrange
        String providerOrderId = "LP-ORD-0000000042";
        localPgServer.withholdCreateResponse();
        try (var requests = Executors.newSingleThreadExecutor()) {
            var waitingCreateResponse = requests.submit(() -> paymentGateway.create(
                    providerOrderId, 7_000, "TEST", "0000-0000-0000-0000"));
            assertThat(localPgServer.awaitCreateRequest()).isTrue();

            try {
                // act
                var storedTransactions = paymentGateway.findByOrderId(providerOrderId);

                // assert
                assertThat(storedTransactions).singleElement()
                        .extracting(transaction -> transaction.status()).isEqualTo(PaymentStatus.PENDING);
                assertThat(waitingCreateResponse).isNotDone();
            } finally {
                localPgServer.releaseResponses();
            }
            waitingCreateResponse.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void 여러_POST가_동시에_거래를_생성해도_서로_다른_거래키를_발급한다() throws Exception {
        // arrange
        int concurrentRequestCount = 4;
        var startRequests = new CountDownLatch(1);
        try (var requests = Executors.newFixedThreadPool(concurrentRequestCount)) {
            var createdTransactions = new ArrayList<Future<String>>();
            for (int requestNumber = 0; requestNumber < concurrentRequestCount; requestNumber++) {
                String providerOrderId = "LP-ORD-" + requestNumber;
                createdTransactions.add(requests.submit(() -> {
                    startRequests.await();
                    return paymentGateway.create(providerOrderId, 7_000, "TEST", "0000-0000-0000-0000")
                            .transactionKey();
                }));
            }

            // act
            startRequests.countDown();
            var transactionKeys = new ArrayList<String>();
            for (var createdTransaction : createdTransactions) {
                transactionKeys.add(createdTransaction.get(2, TimeUnit.SECONDS));
            }

            // assert
            assertThat(transactionKeys).hasSize(concurrentRequestCount).doesNotHaveDuplicates();
        }
    }
}
