package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentRequest;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * PG 와 HTTP 를 주고받는 부분만 맡는다(설계 2.8). 회로·관찰 변환은 {@link PgHttpGateway} 가 한다.
 * 요청마다 POST·GET 을 한 번만 보내고, 본문을 끝까지 받아 연결을 반환한 뒤 돌려준다.
 *
 * <p>통신 실패는 두 가지로 나눠 던진다.
 * <ul>
 *   <li>우리 쪽 연결 풀에서 연결을 얻지 못함 → {@link PgPoolAcquireException}</li>
 *   <li>그 밖의 연결·전송·수신 실패 → 생성은 {@link PgCreateUnavailableException}, 조회는 {@link PgLookupUnavailableException}</li>
 * </ul>
 */
class PgHttpTransport {

    static final String PAYMENTS_PATH = "/api/v1/payments";

    /** 계약상 작은 JSON 만 오므로 이보다 큰 본문은 해석 불가로 다룬다. */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final RestClient restClient;
    private final PgHttpProperties properties;

    PgHttpTransport(RestClient restClient, PgHttpProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    URI createUri() {
        return uri(PAYMENTS_PATH, null, null);
    }

    URI transactionUri(String transactionKey) {
        return uri(PAYMENTS_PATH + "/{transactionKey}", null, transactionKey);
    }

    URI orderLookupUri(String providerOrderId) {
        return uri(PAYMENTS_PATH, "orderId", providerOrderId);
    }

    /** callbackUrl 은 고객 입력이 아니라 서버 설정 값이다. */
    Map<String, Object> createBody(PaymentRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", request.providerOrderId());
        body.put("cardType", request.cardType());
        body.put("cardNo", request.cardNo());
        body.put("amount", request.amount());
        body.put("callbackUrl", properties.callbackUrl().toString());
        return body;
    }

    /**
     * 생성 POST 를 정확히 한 번 보낸다. 풀 대기 실패를 "보내지 않음"으로 판정할 수 있는 것은 이 한 번의 exchange 와
     * PgHttpConfig 의 client 조건(자동 retry·redirect 꺼짐, 재실행 interceptor 없음)에 기대기 때문이다.
     */
    PgHttpResponse post(URI uri, Map<String, Object> body) {
        try {
            return restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((httpRequest, httpResponse) -> new PgHttpResponse(
                    httpResponse.getStatusCode().value(), readBounded(httpResponse.getBody())));
        } catch (ResourceAccessException e) {
            throw classify(e, PgCreateUnavailableException::new);
        }
    }

    PgHttpResponse get(URI uri) {
        try {
            return restClient.get()
                .uri(uri)
                .exchange((httpRequest, httpResponse) -> new PgHttpResponse(
                    httpResponse.getStatusCode().value(), readBounded(httpResponse.getBody())));
        } catch (ResourceAccessException e) {
            throw classify(e, PgLookupUnavailableException::new);
        }
    }

    /**
     * 경로 변수와 query 값을 예약 문자까지 엄격하게 인코딩한다. 그래야 '+'·'&' 같은 문자를 포함한 주문 ID·거래 키도
     * 생성 때와 같은 문자열로 PG 에 전달된다.
     */
    private URI uri(String pathTemplate, String queryName, String variable) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(properties.baseUrl()).path(pathTemplate);
        if (queryName != null) {
            builder.queryParam(queryName, "{value}");
        }
        return variable == null
            ? builder.build().toUri()
            : builder.encode().buildAndExpand(variable).toUri();
    }

    /**
     * RestClient 는 연결·전송·수신 중 I/O 오류를 ResourceAccessException 으로 감싼다. 원인 사슬에 정확히
     * ConnectionRequestTimeoutException 이 있으면 우리 풀 대기 실패이고, 나머지는 PG 통신 실패다. 상위 타입으로는 판정하지 않는다.
     */
    private static RuntimeException classify(ResourceAccessException e,
                                             BiFunction<String, Throwable, ? extends RuntimeException> unavailable) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getClass() == ConnectionRequestTimeoutException.class) {
                return new PgPoolAcquireException(e);
            }
        }
        return unavailable.apply("PG 통신 실패", e);
    }

    private static byte[] readBounded(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            return new byte[0];
        }
        return bytes;
    }
}
