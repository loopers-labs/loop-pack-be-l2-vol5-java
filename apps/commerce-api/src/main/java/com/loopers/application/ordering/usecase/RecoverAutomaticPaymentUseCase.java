package com.loopers.application.ordering.usecase;

import com.loopers.application.ordering.support.AutomaticPaymentExecution;

public interface RecoverAutomaticPaymentUseCase {
    void execute(AutomaticPaymentExecution execution);
}
