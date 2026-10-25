package com.loopers.testpg.infrastructure;

import com.loopers.testpg.domain.Payment;
import com.loopers.testpg.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InMemoryPaymentRepositoryTest {
    @DisplayName("같은 거래의 동시 성공 처리는 한 실행만 성공하고 저장된 결과는 SUCCESS다.")
    @Test
    void completesOnceUnderConcurrentRequests() throws Exception {
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        Payment payment = Payment.create("LP-ORD-0000000042", 7_000L, URI.create("http://localhost/callback"));
        repository.save(payment);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return repository.completeIfPending(payment.getTransactionKey(), PaymentStatus.SUCCESS).isPresent();
                }));
            }
            start.countDown();
            int completed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    completed++;
                }
            }
            assertThat(completed).isEqualTo(1);
        }
        assertThat(repository.findByTransactionKey(payment.getTransactionKey()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.SUCCESS);
        assertThat(repository.findAllByOrderId(payment.getOrderId())).hasSize(1);
    }

    @DisplayName("PENDING 거래는 지정한 결과로 한 번만 종결되고, 이미 종결된 거래는 다른 결과로 바뀌지 않는다.")
    @Test
    void completesWithGivenResultOnlyOnce() {
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        Payment payment = Payment.create("LP-ORD-0000000042", 7_000L, URI.create("http://localhost/callback"));
        repository.save(payment);

        assertThat(repository.completeIfPending(payment.getTransactionKey(), PaymentStatus.FAILED))
            .get().extracting(Payment::getStatus).isEqualTo(PaymentStatus.FAILED);
        assertThat(repository.completeIfPending(payment.getTransactionKey(), PaymentStatus.SUCCESS)).isEmpty();
        assertThat(repository.findByTransactionKey(payment.getTransactionKey()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.FAILED);
    }

    @DisplayName("종결 결과가 아닌 PENDING으로는 종결하지 않는다.")
    @Test
    void rejectsNonTerminalResult() {
        InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
        Payment payment = Payment.create("LP-ORD-0000000042", 7_000L, URI.create("http://localhost/callback"));
        repository.save(payment);

        assertThatIllegalArgumentException()
            .isThrownBy(() -> repository.completeIfPending(payment.getTransactionKey(), PaymentStatus.PENDING));
    }
}
