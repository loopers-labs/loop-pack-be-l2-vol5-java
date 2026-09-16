package com.loopers.interfaces.api.support;

import com.loopers.application.user.UserFacade;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginUserArgumentResolverTest {

    private static final Long EXISTING_USER_ID = 1L;

    // Spring Data의 existsById처럼 null ID를 거절한다. 형식 확인 없이 null을 넘기면 CoreException이 아닌 NPE로 드러난다.
    private final UserRepository userRepository = id -> EXISTING_USER_ID.equals(Objects.requireNonNull(id));

    private final LoginUserArgumentResolver resolver = new LoginUserArgumentResolver(new UserFacade(userRepository));

    private NativeWebRequest requestWithUserIdHeader(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader("X-USER-ID", headerValue);
        }
        return new ServletWebRequest(request);
    }

    @DisplayName("USR-01 · X-USER-ID 헤더로 요청자를 식별할 때, ")
    @Nested
    class Resolve {

        @DisplayName("존재하는 사용자 ID이면, 그 ID를 가진 LoginUser를 반환한다.")
        @Test
        void returnsLoginUser_whenUserExists() {
            // arrange
            NativeWebRequest request = requestWithUserIdHeader(String.valueOf(EXISTING_USER_ID));

            // act
            LoginUser result = resolver.resolveArgument(null, null, request, null);

            // assert
            assertThat(result.id()).isEqualTo(EXISTING_USER_ID);
        }

        @DisplayName("헤더가 없으면, UNAUTHORIZED 예외가 발생한다.")
        @Test
        void throwsUnauthorized_whenHeaderIsMissing() {
            // arrange
            NativeWebRequest request = requestWithUserIdHeader(null);

            // act
            CoreException result = assertThrows(CoreException.class, () -> resolver.resolveArgument(null, null, request, null));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @DisplayName("헤더가 빈칸이면, UNAUTHORIZED 예외가 발생한다.")
        @Test
        void throwsUnauthorized_whenHeaderIsBlank() {
            // arrange
            NativeWebRequest request = requestWithUserIdHeader(" ");

            // act
            CoreException result = assertThrows(CoreException.class, () -> resolver.resolveArgument(null, null, request, null));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @DisplayName("헤더가 숫자가 아니면 (abc), UNAUTHORIZED 예외가 발생한다.")
        @Test
        void throwsUnauthorized_whenHeaderIsNotNumeric() {
            // arrange
            NativeWebRequest request = requestWithUserIdHeader("abc");

            // act
            CoreException result = assertThrows(CoreException.class, () -> resolver.resolveArgument(null, null, request, null));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @DisplayName("헤더가 long 범위를 넘는 숫자이면, UNAUTHORIZED 예외가 발생한다.")
        @Test
        void throwsUnauthorized_whenHeaderOverflowsLong() {
            // arrange
            NativeWebRequest request = requestWithUserIdHeader("9223372036854775808");

            // act
            CoreException result = assertThrows(CoreException.class, () -> resolver.resolveArgument(null, null, request, null));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @DisplayName("존재하지 않는 사용자 ID이면 (999), UNAUTHORIZED 예외가 발생한다.")
        @Test
        void throwsUnauthorized_whenUserDoesNotExist() {
            // arrange
            NativeWebRequest request = requestWithUserIdHeader("999");

            // act
            CoreException result = assertThrows(CoreException.class, () -> resolver.resolveArgument(null, null, request, null));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }
    }
}
