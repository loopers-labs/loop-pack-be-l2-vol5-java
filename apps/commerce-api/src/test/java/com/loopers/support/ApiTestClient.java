package com.loopers.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E 테스트용 클라이언트. X-USER-ID 헤더를 싣고 ApiResponse 봉투를 JsonNode 로 돌려준다.
 */
public class ApiTestClient {
    public static final String USER_HEADER = "X-USER-ID";

    private final TestRestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ApiTestClient(TestRestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public record Result(HttpStatus status, JsonNode body) {
        public JsonNode data() {
            return body.path("data");
        }

        public String errorCode() {
            return body.path("meta").path("errorCode").asText(null);
        }

        public String message() {
            return body.path("meta").path("message").asText(null);
        }

        public String result() {
            return body.path("meta").path("result").asText(null);
        }

        /** 설계 4-4: HTTP 상태와 errorCode, data null, result FAIL 을 한 번에 확인한다. */
        public Result assertError(HttpStatus expectedStatus, String expectedCode) {
            assertThat(status).as("http status").isEqualTo(expectedStatus);
            assertThat(errorCode()).as("errorCode").isEqualTo(expectedCode);
            assertThat(result()).isEqualTo("FAIL");
            assertThat(data().isNull() || data().isMissingNode()).as("data must be null").isTrue();
            return this;
        }

        public Result assertSuccess(HttpStatus expectedStatus) {
            assertThat(status).as("http status").isEqualTo(expectedStatus);
            assertThat(result()).isEqualTo("SUCCESS");
            return this;
        }
    }

    public Result get(String path, Object userId) {
        return exchange(HttpMethod.GET, path, userId, null);
    }

    public Result post(String path, Object userId, Object body) {
        return exchange(HttpMethod.POST, path, userId, body);
    }

    public Result put(String path, Object userId, Object body) {
        return exchange(HttpMethod.PUT, path, userId, body);
    }

    public Result delete(String path, Object userId) {
        return exchange(HttpMethod.DELETE, path, userId, null);
    }

    /** userId 가 null 이면 헤더를 싣지 않는다. body 가 String 이면 JSON 원문으로 그대로 보낸다. */
    public Result exchange(HttpMethod method, String path, Object userId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userId != null) {
            headers.set(USER_HEADER, userId.toString());
        }
        String json;
        try {
            json = body == null ? null : body instanceof String s ? s : objectMapper.writeValueAsString(body);
            ResponseEntity<String> response = restTemplate.exchange(path, method, new HttpEntity<>(json, headers), String.class);
            JsonNode node = response.getBody() == null ? objectMapper.nullNode() : objectMapper.readTree(response.getBody());
            return new Result(HttpStatus.valueOf(response.getStatusCode().value()), node);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
