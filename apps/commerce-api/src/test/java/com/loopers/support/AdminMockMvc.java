package com.loopers.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

public final class AdminMockMvc {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<HttpMethod> SAFE_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);

    private AdminMockMvc() {
    }

    public static ResponseEntity<JsonNode> exchange(MockMvc mvc, HttpMethod method, String path,
                                                     String requester, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return exchange(mvc, method, path, requester, body, headers);
    }

    public static ResponseEntity<JsonNode> exchange(MockMvc mvc, HttpMethod method, String path,
                                                     String requester, String body, HttpHeaders headers) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path).headers(headers);
        if (requester != null && !requester.isBlank()) {
            request.with(user(requester).roles("admin".equals(requester) ? "ADMIN" : "USER"));
        }
        if (!SAFE_METHODS.contains(method)) {
            request.with(csrf());
        }
        if (body != null) {
            request.content(body);
        }
        try {
            var response = mvc.perform(request).andReturn().getResponse();
            HttpHeaders responseHeaders = new HttpHeaders();
            response.getHeaderNames().forEach(name -> responseHeaders.put(name, List.copyOf(response.getHeaders(name))));
            JsonNode responseBody = response.getContentAsByteArray().length == 0
                ? null : JSON.readTree(response.getContentAsByteArray());
            return new ResponseEntity<>(responseBody, responseHeaders, HttpStatusCode.valueOf(response.getStatus()));
        } catch (Exception exception) {
            throw new AssertionError("관리자 MockMvc 요청 실패: " + method + " " + path, exception);
        }
    }
}
