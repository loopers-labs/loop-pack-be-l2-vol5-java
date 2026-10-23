package com.loopers.domain.payment;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class PaymentConflictRepositoryIntegrationTest {

    @Autowired
    private PaymentConflictRepository paymentConflictRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("한 시도에 생긴 충돌을 모두, 기록된 순서대로 읽는다. 다른 시도의 충돌은 섞이지 않는다.")
    @Test
    void findsAllConflictsOfAttemptInOrder() {
        // arrange
        PaymentObservation lateSuccess =
            new PaymentObservation("LP-ORD-0000000042", "TEST-TX-0042", PaymentObservation.Status.SUCCESS, 7000L);
        paymentConflictRepository.save(new PaymentConflict(7L, PaymentConflictReason.DIFFERENT_OUTCOME_AFTER_TERMINAL,
            PaymentAttemptStatus.FAILED, lateSuccess));
        paymentConflictRepository.save(new PaymentConflict(8L, PaymentConflictReason.MULTIPLE_TRANSACTIONS,
            PaymentAttemptStatus.UNKNOWN, null));
        paymentConflictRepository.save(new PaymentConflict(7L, PaymentConflictReason.MISMATCH,
            PaymentAttemptStatus.FAILED, lateSuccess));

        // act
        List<PaymentConflict> conflicts = paymentConflictRepository.findAllByAttemptId(7L);

        // assert
        assertAll(
            () -> assertThat(conflicts).extracting(PaymentConflict::getReason)
                .containsExactly(PaymentConflictReason.DIFFERENT_OUTCOME_AFTER_TERMINAL, PaymentConflictReason.MISMATCH),
            () -> assertThat(conflicts.get(0).getObservedAmount()).isEqualTo(7000L),
            () -> assertThat(conflicts.get(0).getFirstDetectedAt()).isNotNull()
        );
    }
}
