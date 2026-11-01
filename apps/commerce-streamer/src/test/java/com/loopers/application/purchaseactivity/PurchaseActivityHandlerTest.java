package com.loopers.application.purchaseactivity;

import com.loopers.domain.purchaseactivity.OrderConfirmation;
import com.loopers.support.persistence.PurchaseActivityConstraintClassifier;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C4·C5: handler 의 분기만 대역으로 본다. 실제 TX·rollback·DB 예외는 통합 검증에서 확인한다. */
@DisplayName("handler 는 완료 unique 위반만 재확인으로 넘기고 다른 오류는 그대로 전파한다.")
class PurchaseActivityHandlerTest {

    private static final OrderConfirmation EVENT = new OrderConfirmation("OrderConfirmed", 1, "order-confirmed:42", 42,
        ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 0, ZoneOffset.UTC), List.of(new OrderConfirmation.Item(7, 2)));

    private final PurchaseActivityTransactionService transactions = mock(PurchaseActivityTransactionService.class);
    private final PurchaseActivityHandler handler =
        new PurchaseActivityHandler(transactions, new PurchaseActivityConstraintClassifier());

    private static DataIntegrityViolationException violation(String name) {
        return new DataIntegrityViolationException("translated",
            new ConstraintViolationException("hibernate", new SQLException("db"), "sql", ConstraintKind.UNIQUE, name));
    }

    @DisplayName("완료 unique 위반이면 같은 원래 예외로 재확인을 호출하고 그 결과를 반환한다.")
    @Test
    void completionUniqueGoesToVerify() {
        DataIntegrityViolationException failure = violation("processed_order_confirmation.uk_poc_order");
        when(transactions.consume(EVENT)).thenThrow(failure);
        when(transactions.verifyDuplicate(EVENT, failure)).thenReturn(PurchaseActivityResult.ALREADY_PROCESSED);

        assertThat(handler.consume(EVENT)).isEqualTo(PurchaseActivityResult.ALREADY_PROCESSED);
        verify(transactions).verifyDuplicate(EVENT, failure);
    }

    @DisplayName("활동 unique·기술 오류는 재확인하지 않고 같은 예외로 전파한다.")
    @Test
    void otherFailuresPropagate() {
        DataIntegrityViolationException activityUnique = violation("product_purchase_activity.uk_ppa_order_product");
        QueryTimeoutException timeout = new QueryTimeoutException("timeout");
        when(transactions.consume(EVENT)).thenThrow(activityUnique).thenThrow(timeout);

        Throwable first = catchThrowable(() -> handler.consume(EVENT));
        Throwable second = catchThrowable(() -> handler.consume(EVENT));

        assertAll(
            () -> assertThat(first).isSameAs(activityUnique),
            () -> assertThat(second).isSameAs(timeout),
            () -> verify(transactions, never()).verifyDuplicate(any(), any())
        );
    }
}
