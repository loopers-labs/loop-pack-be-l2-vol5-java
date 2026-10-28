package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
