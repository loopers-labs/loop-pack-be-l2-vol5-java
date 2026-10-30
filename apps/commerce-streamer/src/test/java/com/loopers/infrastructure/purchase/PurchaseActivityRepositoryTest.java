package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.DuplicateOrderConfirmationException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseActivityRepositoryTest {
    @Mock
    private ProcessedOrderConfirmationJpaRepository processed;
    @Mock
    private ProductPurchaseActivityJpaRepository activities;

    @DisplayName("DB 오류 코드 없이도 완료 기록의 알려진 유일 제약을 중복으로 변환한다.")
    @ParameterizedTest
    @ValueSource(strings = {
        "uk_processed_confirmation_event", "uk_processed_confirmation_order",
        "processed_order_confirmation.uk_processed_confirmation_event",
        "commerce.processed_order_confirmation.uk_processed_confirmation_order",
        "UK_PROCESSED_CONFIRMATION_EVENT", "COMMERCE.UK_PROCESSED_CONFIRMATION_ORDER"
    })
    void recognizesCompletionUniqueConstraintWithoutVendorErrorCodes(String constraintName) {
        var repository = new PurchaseActivityRepositoryImpl(processed, activities);
        var violation = new ConstraintViolationException("unique", new SQLException("duplicate"),
            ConstraintViolationException.ConstraintKind.UNIQUE, constraintName);
        var failure = new DataIntegrityViolationException("완료 기록 유일 제약", violation);
        when(processed.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> repository.saveCompletion(ProcessedOrderConfirmation.create("event", 42L)))
            .isInstanceOf(DuplicateOrderConfirmationException.class)
            .hasCause(failure);
    }

    @DisplayName("중복 키 오류 번호여도 완료 기록의 제약을 식별하지 못하면 원래 오류를 전달한다.")
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "PRIMARY", "uk_unrelated", "uk_processed_confirmation_event_other"})
    void propagatesUnknownConstraint(String constraintName) {
        var repository = new PurchaseActivityRepositoryImpl(processed, activities);
        var violation = new ConstraintViolationException("unknown constraint",
            new SQLException("duplicate", "23000", 1062),
            ConstraintViolationException.ConstraintKind.UNIQUE, constraintName);
        var failure = new DataIntegrityViolationException("알 수 없는 제약", violation);
        when(processed.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> repository.saveCompletion(ProcessedOrderConfirmation.create("event", 42L)))
            .isSameAs(failure);
    }

    @DisplayName("제약 이름이 같아도 유일 제약 위반으로 분류되지 않으면 원래 오류를 전달한다.")
    @Test
    void propagatesNonUniqueConstraintViolation() {
        var repository = new PurchaseActivityRepositoryImpl(processed, activities);
        var violation = new ConstraintViolationException("other constraint",
            new SQLException("integrity", "23000", 1062),
            ConstraintViolationException.ConstraintKind.OTHER, "uk_processed_confirmation_event");
        var failure = new DataIntegrityViolationException("다른 무결성 오류", violation);
        when(processed.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> repository.saveCompletion(ProcessedOrderConfirmation.create("event", 42L)))
            .isSameAs(failure);
    }

    @DisplayName("중복 키와 SQLState가 같아도 다른 무결성 오류를 중복으로 바꾸지 않는다.")
    @Test
    void propagatesNonDuplicateIntegrityViolation() {
        var repository = new PurchaseActivityRepositoryImpl(processed, activities);
        var failure = new DataIntegrityViolationException("NOT NULL 위반", new SQLException("null", "23000", 1048));
        when(processed.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> repository.saveCompletion(ProcessedOrderConfirmation.create("event", 42L)))
            .isSameAs(failure);
    }
}
