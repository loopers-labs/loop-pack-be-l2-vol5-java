package com.loopers.support.pg;

/** 조회 요청을 실패시키는 방식. 단건 조회와 주문별 조회에 똑같이 적용된다. */
public enum TestPgLookupFailure {
    /** 500과 {@link TestPgContract#SERVER_ERROR} code로 응답한다. */
    SERVER_ERROR,
    /** 응답을 붙잡아 클라이언트가 timeout되게 한다. 풀리면 정상 응답을 보낸다. */
    HOLD,
    /**
     * 응답 헤더를 보내지 않고 연결을 닫는다. JDK {@code HttpClient}는 GET을 이 경우 한 번 다시 보내므로,
     * 클라이언트 호출 한 번에 서버는 조회 요청을 두 번 받는다 (명세서 5-2절).
     */
    DROP,
    /** 200과 깨진 JSON으로 응답한다. */
    INVALID_JSON
}
