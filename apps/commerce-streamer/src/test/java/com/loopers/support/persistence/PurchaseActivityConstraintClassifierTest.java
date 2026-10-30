package com.loopers.support.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** C4·C5: 완료 unique 두 이름만 식별한다. 실제 MySQL/Hibernate 예외 체인은 DB 통합 검증에서 따로 확인한다. */
@DisplayName("제약 분류기는 Hibernate metadata 의 완료 unique 두 이름만 식별하고 나머지는 빈 값이다.")
class PurchaseActivityConstraintClassifierTest {

    private final PurchaseActivityConstraintClassifier classifier = new PurchaseActivityConstraintClassifier();

    private static DataIntegrityViolationException wrapped(ConstraintKind kind, String name) {
        return new DataIntegrityViolationException("translated",
            new ConstraintViolationException("hibernate", new SQLException("db"), "sql", kind, name));
    }

    @DisplayName("테이블 접두어가 있든 없든 uk_poc_event·uk_poc_order 는 식별한다.")
    @Test
    void completionUnique() {
        assertAll(
            () -> assertThat(classifier.completionUniqueViolation(wrapped(ConstraintKind.UNIQUE, "processed_order_confirmation.uk_poc_event")))
                .contains("uk_poc_event"),
            () -> assertThat(classifier.completionUniqueViolation(wrapped(ConstraintKind.UNIQUE, "uk_poc_order"))).contains("uk_poc_order")
        );
    }

    @DisplayName("활동 unique·CHECK·이름 없는 제약·다른 이름·metadata 없는 오류·lock 오류는 식별하지 않는다.")
    @Test
    void otherFailures() {
        assertAll(
            () -> assertThat(classifier.completionUniqueViolation(wrapped(ConstraintKind.UNIQUE, "product_purchase_activity.uk_ppa_order_product"))).isEmpty(),
            () -> assertThat(classifier.completionUniqueViolation(wrapped(ConstraintKind.OTHER, "processed_order_confirmation.uk_poc_event"))).isEmpty(),
            () -> assertThat(classifier.completionUniqueViolation(wrapped(ConstraintKind.OTHER, "ck_poc_item_count"))).isEmpty(),
            () -> assertThat(classifier.completionUniqueViolation(wrapped(ConstraintKind.UNIQUE, null))).isEmpty(),
            () -> assertThat(classifier.completionUniqueViolation(wrapped(ConstraintKind.UNIQUE, "x.uk_poc_event_old"))).isEmpty(),
            () -> assertThat(classifier.completionUniqueViolation(new DataIntegrityViolationException("Duplicate entry for key 'uk_poc_event'")))
                .isEmpty(),
            () -> assertThat(classifier.completionUniqueViolation(new CannotAcquireLockException("deadlock"))).isEmpty()
        );
    }
}
