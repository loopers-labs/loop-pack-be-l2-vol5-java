package com.loopers.domain.payment;

import java.util.List;

/** 거래 조회의 관찰 결과 (docs/week5/pg-contract.md 4-2절). */
public sealed interface PgLookupResult {

    /**
     * 형식이 맞는 응답을 받았다. 0건도 여기에 속한다.
     * 0건은 "지금 보이는 거래가 없다"일 뿐 거래가 영원히 없다는 증거가 아니다.
     */
    record Transactions(List<PgTransaction> transactions) implements PgLookupResult {
        public Transactions {
            transactions = List.copyOf(transactions);
        }
    }

    /** 확인하지 못했다 (timeout, 연결 실패, 서버 오류, 형식 오류, 회로 차단). 결제 결과를 정하는 근거가 아니다. */
    record Unavailable(UnavailableReason reason) implements PgLookupResult {
    }
}
