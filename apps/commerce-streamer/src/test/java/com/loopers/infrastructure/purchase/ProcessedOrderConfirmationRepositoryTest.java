package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.DuplicateOrderConfirmationException;
import com.loopers.domain.purchase.OrderPurchase;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessedOrderConfirmationRepositoryTest {
    private final ProcessedOrderConfirmationJpaRepository jpa = mock(ProcessedOrderConfirmationJpaRepository.class);
    private final ProcessedOrderConfirmationRepositoryImpl repository = new ProcessedOrderConfirmationRepositoryImpl(jpa);

    @ParameterizedTest
    @ValueSource(strings = {"uk_processed_confirmation_event", "`processed_order_confirmation`.`uk_processed_confirmation_order`"})
    void translatesOnlyKnownIdentityDuplicates(String constraint) {
        DataIntegrityViolationException failure = violation(1062, constraint);
        when(jpa.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> repository.save(confirmation()))
            .isInstanceOf(DuplicateOrderConfirmationException.class).hasCause(failure);
    }

    @Test
    void unknownUniqueViolationIsNotReportedAsAlreadyProcessed() {
        DataIntegrityViolationException failure = violation(1062, "other_constraint");
        when(jpa.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> repository.save(confirmation())).isSameAs(failure);
    }

    @Test
    void nonDuplicateIntegrityFailureIsNotReportedAsAlreadyProcessed() {
        DataIntegrityViolationException failure = violation(1048, "uk_processed_confirmation_event");
        when(jpa.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> repository.save(confirmation())).isSameAs(failure);
    }

    private DataIntegrityViolationException violation(int code, String constraint) {
        SQLException sql = new SQLException("test: integrity failure", "23000", code);
        return new DataIntegrityViolationException("test: integrity failure",
            new ConstraintViolationException("test", sql, constraint));
    }

    private ProcessedOrderConfirmation confirmation() {
        return new ProcessedOrderConfirmation(new OrderPurchase("order-confirmed:v1:42", 42L,
            Instant.parse("2026-10-29T01:00:00Z"), List.of(new OrderPurchase.Item(7L, 5))));
    }
}
