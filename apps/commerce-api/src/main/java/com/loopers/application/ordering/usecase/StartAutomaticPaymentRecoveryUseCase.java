package com.loopers.application.ordering.usecase;

/** 최초 PG 발송 권한을 얻은 confirm에서만 호출한다. */
public interface StartAutomaticPaymentRecoveryUseCase {
    void execute(String attemptKey);
}
