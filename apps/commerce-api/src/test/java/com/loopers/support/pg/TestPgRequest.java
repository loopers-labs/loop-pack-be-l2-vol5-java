package com.loopers.support.pg;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 테스트 PG가 받은 요청 하나의 기록이다. 계약 테스트가 메서드·경로·헤더·JSON 타입을 대조할 때 쓴다.
 * 메모리에만 두고 로그로 찍지 않는다.
 *
 * @param path     인코딩된 그대로의 경로
 * @param rawQuery 인코딩된 그대로의 query, 없으면 null
 * @param headers  헤더 이름의 대소문자를 가리지 않는 첫 번째 값 모음
 * @param body     받은 본문 문자열 (본문이 없으면 빈 문자열)
 */
public record TestPgRequest(String method, String path, String rawQuery, Map<String, String> headers, String body) {

    public String header(String name) {
        return headers.get(name);
    }

    /** 본문을 JSON으로 읽는다. 올바른 JSON이 아니면 null이다. */
    public JsonNode json() {
        return TestPgContract.readJson(body);
    }
}
