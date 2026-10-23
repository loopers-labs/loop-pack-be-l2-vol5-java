package com.loopers.infrastructure.payment;

import java.time.Duration;

/**
 * 테스트 PG 연결 설정(docs/week5/recovery.md 2번, D-030). base URL과 callback URL은 로컬 설정으로 고정한다 —
 * 고객이 외부 주소를 넣게 하지 않는다. create와 조회는 응답 timeout이 다르다.
 */
public record PgClientSettings(
    String baseUrl,
    String userId,
    String callbackUrl,
    Duration createConnectTimeout,
    Duration createReadTimeout,
    Duration lookupConnectTimeout,
    Duration lookupReadTimeout,
    Duration lookupRetryInitialWait
) {}
