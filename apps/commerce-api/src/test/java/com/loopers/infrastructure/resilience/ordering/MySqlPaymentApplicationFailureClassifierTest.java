package com.loopers.infrastructure.resilience.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.CannotAcquireLockException;

class MySqlPaymentApplicationFailureClassifierTest {
    private final MySqlPaymentApplicationFailureClassifier classifier = new MySqlPaymentApplicationFailureClassifier();

    @ParameterizedTest
    @CsvSource({"1213,40001,true", "1213,HY000,false", "1205,40001,false", "1062,23000,false", "0,08006,false"})
    @DisplayName("MySQL 오류 번호와 SQLSTATE가 모두 데드락일 때만 식별한다")
    void requiresExactSqlEvidence(int code, String state, boolean expected) {
        var failure = new CannotAcquireLockException("DB 오류", new SQLException("DB 오류", state, code));
        assertThat(classifier.isDeadlock(failure)).isEqualTo(expected);
    }

    @Test
    @DisplayName("JPA의 중첩 원인과 SQL 후속 예외에서도 코드 근거를 찾는다")
    void followsWrappedSqlExceptions() {
        SQLException sql = new SQLException("batch 오류");
        sql.setNextException(new SQLException("deadlock", "40001", 1213));
        assertThat(classifier.isDeadlock(new IllegalStateException(new RuntimeException(sql)))).isTrue();
        assertThat(classifier.isDeadlock(new CannotAcquireLockException("Deadlock 1213 / 40001"))).isFalse();
    }
}
