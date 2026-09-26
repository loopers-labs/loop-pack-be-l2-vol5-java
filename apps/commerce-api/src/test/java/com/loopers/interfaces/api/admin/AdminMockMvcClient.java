package com.loopers.interfaces.api.admin;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.net.URI;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.http.MediaType.APPLICATION_JSON;

public final class AdminMockMvcClient {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public AdminMockMvcClient(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    public <T> ResponseEntity<T> exchange(
        String url,
        HttpMethod method,
        HttpEntity<?> entity,
        ParameterizedTypeReference<T> responseType
    ) {
        try {
            MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, URI.create(url))
                .with(user("admin").roles("ADMIN"));
            if (requiresCsrf(method)) {
                request.with(csrf());
            }
            if (entity != null && entity.getBody() != null) {
                request.contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(entity.getBody()));
            }

            MvcResult result = mockMvc.perform(request).andReturn();
            JavaType bodyType = objectMapper.getTypeFactory().constructType(responseType.getType());
            String content = result.getResponse().getContentAsString();
            T body = content.isBlank() ? null : objectMapper.readValue(content, bodyType);
            return ResponseEntity.status(result.getResponse().getStatus()).body(body);
        } catch (Exception exception) {
            throw new IllegalStateException("MockMvc 관리자 요청 실행에 실패했습니다.", exception);
        }
    }

    private boolean requiresCsrf(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.DELETE;
    }
}
