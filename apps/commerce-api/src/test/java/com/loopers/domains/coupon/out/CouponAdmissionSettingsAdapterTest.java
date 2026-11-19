package com.loopers.domains.coupon.out;

import com.loopers.domains.coupon.app.port.AdmissionUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 설정 행 읽기는 JPA 리포지토리의 readOnly 트랜잭션 안에서 돈다. DB 연결 실패는 DataAccessException이 아니라
// 트랜잭션 시작 실패(CannotCreateTransactionException)로 올 수 있어, 그것도 포트 예외로 바꿔야 503으로 이어진다(29절, D123).
class CouponAdmissionSettingsAdapterTest {

    @Test
    @DisplayName("설정 행을 읽다가 트랜잭션 시작이 실패하면 원인을 담은 AdmissionUnavailableException으로 바꾼다")
    void transactionStartFailureBecomesPortException() {
        CouponAdmissionSettingsJpaRepository repository = mock(CouponAdmissionSettingsJpaRepository.class);
        CannotCreateTransactionException failure = new CannotCreateTransactionException("주입된 DB 연결 실패");
        when(repository.findById(CouponAdmissionSettingsJpaEntity.SINGLE_ROW_ID)).thenThrow(failure);
        CouponAdmissionSettingsAdapter adapter = new CouponAdmissionSettingsAdapter(repository);

        assertThatThrownBy(adapter::current).isInstanceOf(AdmissionUnavailableException.class).hasCause(failure);
    }
}
