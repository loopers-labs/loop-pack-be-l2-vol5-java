package com.loopers.domain.pay.model;

import java.util.Objects;

/** 프리셋과 입력을 해석한 최종 값. 거래가 생긴 뒤에는 바꾸지 않는다. */
public record PaymentScenario(
        boolean rejectBeforeCreate, ProviderPaymentStatus finalStatus, long processingDelayMs,
        ResponseMode createResponseMode, long createResponseDelayMs,
        ResponseMode lookupResponseMode, long lookupResponseDelayMs,
        CallbackMode callbackMode, long callbackDelayMs
) {
    public enum ResponseMode { NORMAL, DELAY, DROP }
    public enum CallbackMode { SEND, DROP }
    public enum Preset {
        NORMAL_SUCCESS, NORMAL_FAILURE, PENDING, DELAYED_SUCCESS, LOST_SUCCESS, LOST_FAILURE, REJECT_BEFORE_CREATE
    }

    public PaymentScenario {
        Objects.requireNonNull(finalStatus, "예정 결과가 필요합니다.");
        Objects.requireNonNull(createResponseMode, "생성 응답 조건이 필요합니다.");
        Objects.requireNonNull(lookupResponseMode, "조회 응답 조건이 필요합니다.");
        Objects.requireNonNull(callbackMode, "callback 조건이 필요합니다.");
        if (processingDelayMs < 0 || processingDelayMs > 60000) {
            throw new IllegalArgumentException("거래 처리 지연은 0~60000ms입니다.");
        }
        validateResponse(createResponseMode, createResponseDelayMs);
        validateResponse(lookupResponseMode, lookupResponseDelayMs);
        if (callbackDelayMs < 0 || callbackDelayMs > 30000
                || (callbackMode == CallbackMode.DROP && callbackDelayMs != 0)) {
            throw new IllegalArgumentException("callback 지연 조건이 올바르지 않습니다.");
        }
        if (finalStatus == ProviderPaymentStatus.PENDING && (processingDelayMs != 0 || callbackMode != CallbackMode.DROP)) {
            throw new IllegalArgumentException("처리 중 유지에는 완료 지연과 callback을 지정할 수 없습니다.");
        }
        if (rejectBeforeCreate && (finalStatus != ProviderPaymentStatus.FAILED || processingDelayMs != 0
                || createResponseMode != ResponseMode.NORMAL || lookupResponseMode != ResponseMode.NORMAL
                || callbackMode != CallbackMode.DROP)) {
            throw new IllegalArgumentException("생성 전 거절에는 거래 처리나 전달 조건을 지정할 수 없습니다.");
        }
    }

    public static PaymentScenario resolve(Preset preset, Overrides overrides) {
        Preset selected = preset == null ? Preset.NORMAL_SUCCESS : preset;
        PaymentScenario base = switch (selected) {
            case NORMAL_SUCCESS -> base(ProviderPaymentStatus.SUCCESS, 0, ResponseMode.NORMAL, 0, CallbackMode.SEND);
            case NORMAL_FAILURE -> base(ProviderPaymentStatus.FAILED, 0, ResponseMode.NORMAL, 0, CallbackMode.SEND);
            case PENDING -> base(ProviderPaymentStatus.PENDING, 0, ResponseMode.NORMAL, 0, CallbackMode.DROP);
            case DELAYED_SUCCESS -> base(ProviderPaymentStatus.SUCCESS, 3000, ResponseMode.DELAY, 4000, CallbackMode.SEND);
            case LOST_SUCCESS -> base(ProviderPaymentStatus.SUCCESS, 1000, ResponseMode.DROP, 0, CallbackMode.SEND);
            case LOST_FAILURE -> base(ProviderPaymentStatus.FAILED, 1000, ResponseMode.DROP, 0, CallbackMode.SEND);
            case REJECT_BEFORE_CREATE -> new PaymentScenario(true, ProviderPaymentStatus.FAILED, 0,
                    ResponseMode.NORMAL, 0, ResponseMode.NORMAL, 0, CallbackMode.DROP, 0);
        };
        if (overrides == null) {
            return base;
        }
        return new PaymentScenario(value(overrides.rejectBeforeCreate(), base.rejectBeforeCreate()),
                value(overrides.finalStatus(), base.finalStatus()),
                value(overrides.processingDelayMs(), base.processingDelayMs()),
                value(overrides.createResponseMode(), base.createResponseMode()),
                value(overrides.createResponseDelayMs(), base.createResponseDelayMs()),
                value(overrides.lookupResponseMode(), base.lookupResponseMode()),
                value(overrides.lookupResponseDelayMs(), base.lookupResponseDelayMs()),
                value(overrides.callbackMode(), base.callbackMode()),
                value(overrides.callbackDelayMs(), base.callbackDelayMs()));
    }

    private static PaymentScenario base(ProviderPaymentStatus status, long processing, ResponseMode mode,
                                        long delay, CallbackMode callback) {
        return new PaymentScenario(false, status, processing, mode, delay, ResponseMode.NORMAL, 0, callback, 0);
    }

    private static <T> T value(T input, T fallback) {
        return input == null ? fallback : input;
    }

    private static void validateResponse(ResponseMode mode, long delay) {
        if (delay < 0 || delay > 30000 || (mode == ResponseMode.DELAY ? delay == 0 : delay != 0)) {
            throw new IllegalArgumentException("응답 지연은 DELAY에만 1~30000ms로 지정합니다.");
        }
    }

    public record Overrides(Boolean rejectBeforeCreate, ProviderPaymentStatus finalStatus, Long processingDelayMs,
                            ResponseMode createResponseMode, Long createResponseDelayMs,
                            ResponseMode lookupResponseMode, Long lookupResponseDelayMs,
                            CallbackMode callbackMode, Long callbackDelayMs) {}
}
