package com.loopers.support.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.loopers.user.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 테스트가 함께 쓰는 요청자, 응답 확인, 응답 본문 읽기다.
 */
public final class ApiHttp {

    public static final String USER_HEADER = "X-USER-ID";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ApiHttp() {
    }

    public static RequestPostProcessor customer(User user) {
        return customer(String.valueOf(user.getId()));
    }

    public static RequestPostProcessor customer(String userIdHeader) {
        return request -> {
            if (userIdHeader != null) {
                request.addHeader(USER_HEADER, userIdHeader);
            }
            return request;
        };
    }

    public static RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    public static RequestPostProcessor nonAdmin() {
        return user("customer").roles("USER");
    }

    public static ResultMatcher success(HttpStatus expected) {
        return result -> {
            status().is(expected.value()).match(result);
            jsonPath("$.meta.result").value("SUCCESS").match(result);
        };
    }

    public static ResultMatcher failure(HttpStatus expected, String errorCode) {
        return result -> {
            status().is(expected.value()).match(result);
            jsonPath("$.meta.result").value("FAIL").match(result);
            jsonPath("$.meta.errorCode").value(errorCode).match(result);
        };
    }

    public static String content(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static JsonNode body(MvcResult result) {
        String content = content(result);
        if (content.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return OBJECT_MAPPER.readTree(content);
        } catch (JsonProcessingException exception) {
            return MissingNode.getInstance();
        }
    }

    public static JsonNode data(MvcResult result) {
        return body(result).path("data");
    }

    public static List<Long> ids(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    public static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    public static JsonNode findById(JsonNode items, Long id) {
        for (JsonNode item : items) {
            if (item.path("id").asLong() == id) {
                return item;
            }
        }
        return MissingNode.getInstance();
    }

    public static JsonNode findByProductId(JsonNode items, Long productId) {
        for (JsonNode item : items) {
            if (item.path("productId").asLong() == productId) {
                return item;
            }
        }
        return MissingNode.getInstance();
    }

    public static boolean isNullOrAbsent(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull();
    }
}
