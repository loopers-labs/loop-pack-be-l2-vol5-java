package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueQueueScriptException;
import com.loopers.application.coupon.CouponIssueQueueUnavailableException;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCouponIssueQueueExceptionTranslatorTest {

    @DisplayName("대기열 키 타입 오류는 스크립트 오류로 구분한다.")
    @Test
    void classifiesWrongTypeAsScriptFailure() {
        RuntimeException translated = RedisCouponIssueQueueExceptionTranslator.translate(
            new RedisCommandExecutionException("WRONGTYPE Operation against a key holding the wrong kind of value"));

        assertThat(translated).isInstanceOf(CouponIssueQueueScriptException.class);
    }

    @DisplayName("대기열 Lua가 돌려준 오류는 Spring 예외로 감싸져 있어도 스크립트 오류로 구분한다.")
    @Test
    void classifiesQueueScriptErrorAsScriptFailure() {
        RuntimeException translated = RedisCouponIssueQueueExceptionTranslator.translate(new RedisSystemException(
            "script failed", new RedisCommandExecutionException("coupon queue: request index without job")));

        assertThat(translated).isInstanceOf(CouponIssueQueueScriptException.class);
    }

    @DisplayName("읽기 전용 primary 오류는 저장소 확인 불가로 구분한다.")
    @Test
    void classifiesReadOnlyAsUnavailable() {
        RuntimeException translated = RedisCouponIssueQueueExceptionTranslator.translate(
            new RedisCommandExecutionException("READONLY You can't write against a read only replica."));

        assertThat(translated).isInstanceOf(CouponIssueQueueUnavailableException.class);
    }

    @DisplayName("명령 timeout처럼 응답을 받지 못한 오류는 저장소 확인 불가로 구분한다.")
    @Test
    void classifiesTimeoutAsUnavailable() {
        RuntimeException translated = RedisCouponIssueQueueExceptionTranslator.translate(
            new RedisSystemException("timeout", new RedisCommandTimeoutException("Command timed out after 100 ms")));

        assertThat(translated).isInstanceOf(CouponIssueQueueUnavailableException.class);
    }
}
