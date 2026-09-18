package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class AdminAccessDeniedResponse {
    private final ObjectMapper mapper;
    public void write(HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), ApiResponse.fail("ACCESS_DENIED", "접근 권한이 없습니다."));
    }
}
