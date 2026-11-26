package com.loopers.infrastructure.payment.persistence;

import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.enums.PaymentStatus;
import com.loopers.domain.payment.repository.PaymentRepository;
import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PaymentRepositoryIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("미종결 상태별 수는 DB 의 상태 그대로 세고, 없는 상태는 0, 종결 상태는 담지 않는다.")
    @Test
    void countsUnresolvedByStatus() {
        // arrange
        transactionTemplate.executeWithoutResult(status -> {
            paymentRepository.save(Payment.request(1L, "attempt-1", 1_000L));
            for (long orderId = 2; orderId <= 3; orderId++) {
                Payment payment = Payment.request(orderId, "attempt-" + orderId, 1_000L);
                payment.apply(new PaymentCreateResult.Unknown(), ZonedDateTime.now());
                paymentRepository.save(payment);
            }
            paymentRepository.save(Payment.notRequired(4L, "attempt-4"));
        });

        // act
        Map<PaymentStatus, Long> counts = paymentRepository.countUnresolvedByStatus();

        // assert
        assertThat(counts).isEqualTo(Map.of(
            PaymentStatus.REQUESTED, 1L,
            PaymentStatus.UNKNOWN, 2L,
            PaymentStatus.PENDING, 0L
        ));
    }
}
