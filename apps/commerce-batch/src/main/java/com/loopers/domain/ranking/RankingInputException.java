package com.loopers.domain.ranking;

import lombok.Getter;

@Getter
public class RankingInputException extends RuntimeException {

    public enum Reason {
        /** 요청 형식·기간·revision·snapshotId 오류. */
        INVALID_REQUEST,
        /** 같은 snapshotId 와 기간·revision 이 서로 다른 저장 행을 가리킨다. */
        SNAPSHOT_CONTRACT_MISMATCH,
        /** 저장된 입력·manifest 가 불변식을 지키지 않는다. */
        INCONSISTENT_INPUT
    }

    private final Reason reason;

    public RankingInputException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
