package com.loopers.domains.payment.app;

import com.loopers.common.error.NotFoundException;

public class RefundCaseNotFoundException extends NotFoundException {
    public RefundCaseNotFoundException() { super("환불 확인 대상을 찾을 수 없습니다."); }
}
