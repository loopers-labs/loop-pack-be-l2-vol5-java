package com.loopers.domain.payment;

/**
 * PG 관찰 또는 로컬 미발송 증거의 값 계약(2.7·4.3). HTTP·JSON 타입과 원문 예외를 노출하지 않는다.
 * 저장 상태·기대 금액과의 비교와 최종 충돌 코드는 TX B 가 정한다.
 */
public sealed interface PaymentObservation {

    ObservationSource source();

    /** 형식 검증을 마친 거래 하나. */
    record TransactionObserved(
        ObservationSource source,
        String transactionKey,
        String providerOrderId,
        long amount,
        PgTransactionStatus status
    ) implements PaymentObservation {

        public TransactionObserved {
            requireSource(source);
            if (transactionKey == null || providerOrderId == null || status == null) {
                throw new IllegalArgumentException("거래 관찰의 키·주문·상태 필수");
            }
        }
    }

    /** 결과를 확인하지 못함: 통신 실패·해석 불가·계약 외 응답·거래 부재. 성공·실패 확정이나 재발송의 근거가 아니다. */
    record Unavailable(ObservationSource source) implements PaymentObservation {

        public Unavailable {
            requireSource(source);
        }
    }

    record Conflict(ObservationSource source, PaymentConflictCode code, ConflictEvidence evidence)
        implements PaymentObservation {

        public Conflict {
            requireSource(source);
            if (code == null || evidence == null) {
                throw new IllegalArgumentException("충돌 코드·근거 필수");
            }
        }
    }

    /** 검증한 정확한 422 생성 전 거절. 외부 효과 0건의 증거이며 생성에서만 나온다. */
    record RejectedNoEffect(String providerOrderId, long amount) implements PaymentObservation {

        @Override
        public ObservationSource source() {
            return ObservationSource.CREATE;
        }
    }

    /** 생성 POST 가 확실히 나가지 않았다는 로컬 증거. PG 응답이 아니다. */
    record LocalNotDispatched(LocalNotDispatchedReason reason) implements PaymentObservation {

        public LocalNotDispatched {
            if (reason == null) {
                throw new IllegalArgumentException("미발송 원인 필수");
            }
        }

        @Override
        public ObservationSource source() {
            return ObservationSource.CREATE;
        }
    }

    private static void requireSource(ObservationSource source) {
        if (source == null) {
            throw new IllegalArgumentException("관찰 출처 필수");
        }
    }
}
