package com.loopers.application.ranking.support;

public class SnapshotFreezeRetryableException extends RuntimeException {
    public SnapshotFreezeRetryableException(Throwable cause) {
        super("입력 고정의 일시적 DB 충돌입니다. 같은 snapshot 식별값으로 다시 실행할 수 있습니다.", cause);
    }
}
