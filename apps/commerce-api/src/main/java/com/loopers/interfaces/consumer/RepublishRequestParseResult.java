package com.loopers.interfaces.consumer;

public sealed interface RepublishRequestParseResult {
    record Parsed(long orderId) implements RepublishRequestParseResult { }

    // 고칠 수 없는 record. 로그·지표를 남기고 넘어간다.
    record Invalid(String detail) implements RepublishRequestParseResult { }

    // 받는 쪽이 모르는 명령 종류·버전. 받는 쪽을 고쳐 배포하면 같은 record로 처리되므로 멈춘다.
    record Unsupported(String detail) implements RepublishRequestParseResult { }
}
