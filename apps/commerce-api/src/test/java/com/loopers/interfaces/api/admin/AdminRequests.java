package com.loopers.interfaces.api.admin;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 관리자 권한(ROLE_ADMIN)을 붙인 MockMvc 요청. 변경 요청에는 CSRF 입력을 함께 넣는다.
 * 요청자 구분 자체는 {@link AdminBoundaryTest}에서 확인한다.
 */
final class AdminRequests {

    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    private AdminRequests() {}

    static MockHttpServletRequestBuilder adminGet(String url) {
        return get(url).with(ADMIN);
    }

    static MockHttpServletRequestBuilder adminPost(String url, String json) {
        return post(url).with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    static MockHttpServletRequestBuilder adminPut(String url, String json) {
        return put(url).with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    static MockHttpServletRequestBuilder adminDelete(String url) {
        return delete(url).with(ADMIN).with(csrf());
    }
}
