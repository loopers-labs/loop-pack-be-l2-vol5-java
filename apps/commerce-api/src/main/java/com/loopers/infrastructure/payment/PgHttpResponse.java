package com.loopers.infrastructure.payment;

/** 본문을 끝까지 받은 PG HTTP 응답. 해석은 연결을 반환한 뒤 decoder 가 한다. */
record PgHttpResponse(int status, byte[] body) {
}
