package com.loopers.support.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;

/**
 * 테스트 PG의 HTTP 계약(docs/week5/pg-contract.md 3절)을 코드로 옮긴 것이다.
 * 경로·헤더·code 값, 생성 요청의 형식 검사, 응답 JSON의 모양을 여기에만 둔다. 명세서 3절과 이 클래스를 1:1로 대조할 수 있게 한다.
 */
public final class TestPgContract {

    public static final String PAYMENTS_PATH = "/api/v1/payments";

    /** 제공자 계정을 담는 헤더. commerce의 고객 식별 헤더와 이름만 같고 관계가 없다. */
    public static final String ACCOUNT_HEADER = "X-USER-ID";

    /** adapter가 보내는 합성 제공자 계정. */
    public static final String PROVIDER_ACCOUNT = "135135";

    /** 거래를 만들기 전에 확정 거절했다는 422 응답의 code. */
    public static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";

    public static final String INVALID_REQUEST = "TEST_PG_INVALID_REQUEST";

    public static final String NOT_FOUND = "TEST_PG_NOT_FOUND";

    /** 조회 실패 통제({@link TestPgLookupFailure#SERVER_ERROR})가 돌려주는 500 응답의 code. */
    public static final String SERVER_ERROR = "TEST_PG_SERVER_ERROR";

    private static final int MIN_ORDER_ID_LENGTH = 6;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 형식 검사를 통과한 생성 요청. 카드번호는 담지 않는다. */
    record CreateInput(String account, String orderId, String cardType, long amount) {
    }

    private TestPgContract() {
    }

    /**
     * 생성 요청이 3-1·3-2절의 필드와 타입을 지키는지 확인한다. 하나라도 어긋나면 null이다.
     * 계정 헤더가 있고, 본문이 JSON 객체이며, orderId는 6자 이상 문자열, cardType·cardNo·callbackUrl은 비어 있지 않은 문자열,
     * amount는 0보다 큰 정수 number여야 한다.
     */
    static CreateInput validateCreate(String account, String body) {
        JsonNode json = readJson(body);
        if (isBlank(account) || json == null || !json.isObject()) {
            return null;
        }
        JsonNode orderId = json.get("orderId");
        JsonNode amount = json.get("amount");
        boolean valid = isNonBlankText(orderId) && orderId.asText().length() >= MIN_ORDER_ID_LENGTH
            && isNonBlankText(json.get("cardType"))
            && isNonBlankText(json.get("cardNo"))
            && isNonBlankText(json.get("callbackUrl"))
            && amount != null && amount.isIntegralNumber() && amount.canConvertToLong() && amount.asLong() > 0;
        if (!valid) {
            return null;
        }
        return new CreateInput(account, orderId.asText(), json.get("cardType").asText(), amount.asLong());
    }

    /** 생성 순서로 거래 키를 만든다 ({@code TEST-TX-} + 4자리 이상). */
    static String transactionKey(long sequence) {
        return String.format("TEST-TX-%04d", sequence);
    }

    /** 3-2·3-3절의 거래 객체. */
    static String transaction(TestPgTransaction transaction) {
        return toNode(transaction).toString();
    }

    /** 3-4절의 거래 배열. 비어 있으면 {@code []}다. */
    static String transactions(List<TestPgTransaction> transactions) {
        ArrayNode list = JSON.createArrayNode();
        transactions.forEach(transaction -> list.add(toNode(transaction)));
        return list.toString();
    }

    /** 3-2절의 거래 생성 전 확정 거절 본문. */
    static String rejection(CreateInput input) {
        ObjectNode node = JSON.createObjectNode();
        node.put("code", REJECTED_BEFORE_CREATE);
        node.put("orderId", input.orderId());
        node.put("amount", input.amount());
        node.put("transactionCreated", false);
        return node.toString();
    }

    /** 3-5절의 결과 알림 본문. */
    static String callback(TestPgTransaction transaction) {
        ObjectNode node = JSON.createObjectNode();
        node.put("transactionKey", transaction.transactionKey());
        node.put("orderId", transaction.orderId());
        node.put("status", transaction.status().name());
        return node.toString();
    }

    static String error(String code) {
        return JSON.createObjectNode().put("code", code).toString();
    }

    /** 본문을 JSON으로 읽는다. 비어 있거나 올바른 JSON이 아니면 null이다. */
    static JsonNode readJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ObjectNode toNode(TestPgTransaction transaction) {
        ObjectNode node = JSON.createObjectNode();
        node.put("transactionKey", transaction.transactionKey());
        node.put("orderId", transaction.orderId());
        node.put("amount", transaction.amount());
        node.put("status", transaction.status().name());
        return node;
    }

    private static boolean isNonBlankText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }
}
