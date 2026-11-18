package com.loopers.support.error;

/** 발급의 영구 오류(업무 판정: 캠페인이 없거나 열려 있지 않음). 재시도하지 않는다 (8주차 D-126) */
public class IssueRejectedException extends RuntimeException {

    public IssueRejectedException(String message) {
        super(message);
    }
}
