package com.loopers.support.http;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.MockMvcRequest;
import com.atlassian.oai.validator.mockmvc.MockMvcResponse;
import com.atlassian.oai.validator.mockmvc.OpenApiMatchers;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.loopers.user.domain.User;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.AntPathMatcher;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 테스트가 함께 쓰는 요청자, 응답 확인, 응답 본문 읽기다.
 */
public final class ApiHttp {

    public static final String USER_HEADER = "X-USER-ID";

    private static final OpenApiInteractionValidator OPEN_API_VALIDATOR = OpenApiInteractionValidator
        .createForSpecificationUrl("/static/openapi.yaml")
        .build();
    private static final ResultMatcher OPEN_API_INTERACTION = new OpenApiMatchers().isValid(OPEN_API_VALIDATOR);
    private static final OpenAPI OPEN_API = loadOpenApi();
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
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
            OPEN_API_INTERACTION.match(result);
            status().is(expected.value()).match(result);
            jsonPath("$.meta.result").value("SUCCESS").match(result);
        };
    }

    public static ResultMatcher failure(HttpStatus expected, String errorCode) {
        return result -> {
            validateErrorResponse(result, errorCode);
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

    private static void validateErrorResponse(MvcResult result, String errorCode) {
        if ("NOT_FOUND".equals(errorCode) || "METHOD_NOT_ALLOWED".equals(errorCode)) {
            return;
        }

        Request request = MockMvcRequest.of(result.getRequest());
        Response response = MockMvcResponse.of(result.getResponse());
        ValidationReport report = OPEN_API_VALIDATOR.validateResponse(
            request.getPath(),
            request.getMethod(),
            response
        );
        if (report.hasErrors()) {
            throw new AssertionError("OpenAPI response contract violation: " + report.getMessages());
        }
        validateAllowedErrorCode(result, errorCode);
    }

    private static void validateAllowedErrorCode(MvcResult result, String errorCode) {
        String requestPath = result.getRequest().getRequestURI();
        PathItem pathItem = OPEN_API.getPaths().entrySet().stream()
            .filter(entry -> PATH_MATCHER.match(entry.getKey(), requestPath))
            .map(java.util.Map.Entry::getValue)
            .findFirst()
            .orElseThrow(() -> new AssertionError("OpenAPI path not found: " + requestPath));
        PathItem.HttpMethod method = PathItem.HttpMethod.valueOf(result.getRequest().getMethod());
        Operation operation = pathItem.readOperationsMap().get(method);
        String statusCode = String.valueOf(result.getResponse().getStatus());
        ApiResponse response = operation.getResponses().get(statusCode);
        if (response == null) {
            throw new AssertionError("OpenAPI response not found: " + method + " " + requestPath + " " + statusCode);
        }
        if (response.get$ref() != null) {
            String componentName = response.get$ref().substring(response.get$ref().lastIndexOf('/') + 1);
            response = OPEN_API.getComponents().getResponses().get(componentName);
        }
        Object declaredCodes = response.getExtensions() == null
            ? null
            : response.getExtensions().get("x-error-codes");
        if (!(declaredCodes instanceof List<?> codes) || !codes.contains(errorCode)) {
            throw new AssertionError("OpenAPI does not allow " + errorCode + " for "
                + method + " " + requestPath + " " + statusCode + ": " + declaredCodes);
        }
    }

    private static OpenAPI loadOpenApi() {
        URL specification = Objects.requireNonNull(
            ApiHttp.class.getResource("/static/openapi.yaml"),
            "OpenAPI specification is missing"
        );
        OpenAPI openApi = new OpenAPIV3Parser().read(specification.toExternalForm());
        return Objects.requireNonNull(openApi, "OpenAPI specification could not be parsed");
    }
}
