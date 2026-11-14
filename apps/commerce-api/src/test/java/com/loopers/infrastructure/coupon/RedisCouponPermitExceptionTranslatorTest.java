package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponPermitScriptException;
import com.loopers.application.coupon.CouponPermitStoreUnavailableException;
import io.lettuce.core.RedisCommandExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCouponPermitExceptionTranslatorTest {

    @DisplayName("permit Set 키 타입 오류는 내부 스크립트 오류로 구분한다.")
    @Test
    void classifiesWrongTypeAsScriptFailure() {
        RuntimeException translated = RedisCouponPermitExceptionTranslator.translate(
            new RedisCommandExecutionException("WRONGTYPE Operation against a key holding the wrong kind of value"));

        assertThat(translated).isInstanceOf(CouponPermitScriptException.class);
    }

    @DisplayName("Lua 입력 검증 오류는 내부 스크립트 오류로 구분한다.")
    @Test
    void classifiesLuaInputValidationAsScriptFailure() {
        RuntimeException translated = RedisCouponPermitExceptionTranslator.translate(
            new RedisCommandExecutionException("ERR invalid admission input"));

        assertThat(translated).isInstanceOf(CouponPermitScriptException.class);
    }

    @DisplayName("읽기 전용 primary 오류는 Redis 상태 확인 불가로 구분한다.")
    @Test
    void classifiesReadOnlyAsUnavailable() {
        RuntimeException translated = RedisCouponPermitExceptionTranslator.translate(
            new RedisCommandExecutionException("READONLY You can't write against a read only replica."));

        assertThat(translated).isInstanceOf(CouponPermitStoreUnavailableException.class);
    }

    @DisplayName("Redis 로딩 오류는 스크립트 결함이 아니라 상태 확인 불가로 구분한다.")
    @Test
    void classifiesLoadingAsUnavailable() {
        RuntimeException translated = RedisCouponPermitExceptionTranslator.translate(
            new RedisCommandExecutionException("LOADING Redis is loading the dataset in memory"));

        assertThat(translated).isInstanceOf(CouponPermitStoreUnavailableException.class);
    }
}
