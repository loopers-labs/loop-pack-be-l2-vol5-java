package com.loopers.interfaces.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiControllerAdviceTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // @SpringBootTest / @WebMvcTest 대신 standalone 으로 직접 조립한다.
        // 스프링 컨텍스트와 Testcontainers(Docker) 없이 ApiControllerAdvice 의 예외 변환만 빠르게 검증하기 위함이다.
        mockMvc = MockMvcBuilders.standaloneSetup(new HeaderTestController())
            .setControllerAdvice(new ApiControllerAdvice())
            .build();
    }

    // 시나리오(헤더 처리)별로 묶어 테스트 트리를 문서처럼 읽히게 한다. 이후 다른 예외 처리도 @Nested 로 추가한다.
    @DisplayName("X-USER-ID 헤더를 받는 API 에서,")
    @Nested
    class UserIdHeader {
        @DisplayName("헤더가 없으면 INVALID_INPUT(400)으로 응답한다.")
        @Test
        void returnsInvalidInput_whenHeaderIsMissing() throws Exception {
            mockMvc.perform(get("/test/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
        }

        @DisplayName("헤더 값이 숫자가 아니면 INVALID_INPUT(400)으로 응답한다.")
        @Test
        void returnsInvalidInput_whenHeaderIsNotNumber() throws Exception {
            mockMvc.perform(get("/test/header").header("X-USER-ID", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
        }

        @DisplayName("유효한 헤더면 정상 응답한다.")
        @Test
        void returnsOk_whenHeaderIsValid() throws Exception {
            mockMvc.perform(get("/test/header").header("X-USER-ID", "1"))
                .andExpect(status().isOk());
        }
    }

    @DisplayName("@Valid 요청 본문을 받는 API 에서,")
    @Nested
    class RequestBodyValidation {
        @DisplayName("@NotNull 필드가 없으면 INVALID_INPUT(400)으로 응답하고, 메시지에 필드명이 들어간다.")
        @Test
        void returnsInvalidInput_withFieldName_whenRequiredFieldIsMissing() throws Exception {
            mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.meta.message").value(containsString("name")));
        }

        @DisplayName("필수 필드가 모두 있으면 정상 응답한다.")
        @Test
        void returnsOk_whenRequiredFieldExists() throws Exception {
            mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\"}"))
                .andExpect(status().isOk());
        }
    }

    @DisplayName("JSON 본문을 읽지 못하는 API 에서,")
    @Nested
    class UnreadableBody {
        private void assertInvalidInput(String url, String json, String... messageParts) throws Exception {
            var result = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
            for (String part : messageParts) {
                result.andExpect(jsonPath("$.meta.message").value(containsString(part)));
            }
        }

        @DisplayName("정수 필드가 int 범위를 넘으면 INVALID_INPUT(400)이고, 메시지에 필드 위치와 값이 들어간다.")
        @Test
        void returnsInvalidInput_withFieldPath_whenIntegerOverflows() throws Exception {
            assertInvalidInput("/test/amount", "{\"amount\":9223372036854775808}", "'amount'", "9223372036854775808", "범위");
            assertInvalidInput("/test/items", "{\"items\":[{\"productId\":1,\"quantity\":3000000000}]}",
                "'items[0].quantity'", "3000000000", "범위");
        }

        @DisplayName("배열 원소의 필드 위치는 몇 번째 원소인지(인덱스)까지 알려준다.")
        @Test
        void returnsInvalidInput_withElementIndex_whenArrayElementIsInvalid() throws Exception {
            String json = "{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":2,\"quantity\":%s}]}";

            assertInvalidInput("/test/items", String.format(json, "3000000000"), "'items[1].quantity'");
            assertInvalidInput("/test/items", String.format(json, "\"abc\""), "'items[1].quantity'", "abc", "Integer");
            // 소수(1.5) 거절은 앱의 ObjectMapper 설정이라 standalone 에서는 재현되지 않는다. OrderApiE2ETest 가 검증한다.
        }

        @DisplayName("배열이 아닌 필드의 위치는 지금처럼 필드명이다.")
        @Test
        void returnsInvalidInput_withFieldName_whenFieldTypeIsWrong() throws Exception {
            assertInvalidInput("/test/amount", "{\"amount\":\"abc\"}", "'amount'", "abc", "Long");
        }

        @DisplayName("JSON 형식 자체가 깨지면 규격을 확인하라는 일반 메시지로 INVALID_INPUT(400)이다.")
        @Test
        void returnsInvalidInput_withGeneralMessage_whenJsonIsBroken() throws Exception {
            assertInvalidInput("/test/amount", "{\"amount\":", "JSON 메세지 규격");
        }
    }

    // 실제 Controller 는 아직 없으므로, 헤더를 받는 최소한의 테스트 전용 Controller 를 둔다.
    // 바깥 테스트 클래스의 인스턴스에 묶이지 않도록 static 중첩 클래스로 선언한다.
    @RestController
    static class HeaderTestController {
        @GetMapping("/test/header")
        public String get(@RequestHeader("X-USER-ID") Long userId) {
            return String.valueOf(userId);
        }

        @PostMapping("/test/body")
        public String post(@Valid @RequestBody TestRequest request) {
            return request.name();
        }

        @PostMapping("/test/amount")
        public String amount(@RequestBody AmountRequest request) {
            return String.valueOf(request.amount());
        }

        @PostMapping("/test/items")
        public String items(@RequestBody ItemsRequest request) {
            return String.valueOf(request.items().size());
        }
    }

    record TestRequest(@NotNull String name) {}

    record AmountRequest(Long amount) {}

    record ItemRequest(Long productId, Integer quantity) {}

    record ItemsRequest(List<ItemRequest> items) {}
}
