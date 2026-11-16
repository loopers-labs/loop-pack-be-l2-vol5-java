package com.loopers.support.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("제약 분류기는 허용 목록의 정확한 unique 이름만 식별한다.")
class ConstraintViolationClassifierTest {

    private final ConstraintViolationClassifier classifier = new ConstraintViolationClassifier();

    @DisplayName("쿠폰 named unique 를 테이블 접두어와 관계없이 식별하고 기존 결제 제약 식별도 유지한다.")
    @Test
    void identifiesCouponAndPaymentUnique() {
        assertAll(
            () -> assertThat(classifier.namedUniqueViolation(wrapped("coupon.uk_coupon_campaign_user")))
                .contains("uk_coupon_campaign_user"),
            () -> assertThat(classifier.namedUniqueViolation(wrapped("uk_coupon_campaign_user")))
                .contains("uk_coupon_campaign_user"),
            () -> assertThat(classifier.namedUniqueViolation(wrapped("payment.uk_payment_order"))).contains("uk_payment_order"),
            () -> assertThat(classifier.namedUniqueViolation(wrapped("payment.uk_payment_key"))).contains("uk_payment_key"),
            () -> assertThat(classifier.namedUniqueViolation(wrapped("payment.uk_payment_transaction")))
                .contains("uk_payment_transaction"),
            () -> assertThat(classifier.namedUniqueViolation(wrapped("payment_conflict.uk_payment_conflict_payment")))
                .contains("uk_payment_conflict_payment")
        );
    }

    @DisplayName("CHECK·다른 이름·비슷한 이름·이름 없음·제약 예외가 아닌 오류는 식별하지 않는다.")
    @Test
    void ignoresOtherFailures() {
        assertAll(
            () -> assertThat(classifier.namedUniqueViolation(wrapped("coupon_campaign.ck_coupon_campaign_quota"))).isEmpty(),
            () -> assertThat(classifier.namedUniqueViolation(wrapped("coupon.uk_coupon_campaign_user_x"))).isEmpty(),
            () -> assertThat(classifier.namedUniqueViolation(wrapped("coupon.PRIMARY"))).isEmpty(),
            () -> assertThat(classifier.namedUniqueViolation(wrapped(null))).isEmpty(),
            () -> assertThat(classifier.namedUniqueViolation(new DataIntegrityViolationException("uk_coupon_campaign_user")))
                .isEmpty(),
            () -> assertThat(classifier.namedUniqueViolation(new IllegalStateException("connection reset"))).isEmpty()
        );
    }

    private static Throwable wrapped(String constraintName) {
        ConstraintViolationException violation = new ConstraintViolationException(
            "violation", new SQLException("duplicate", "23000", 1062), constraintName);
        return new DataIntegrityViolationException("could not execute statement", violation);
    }
}
