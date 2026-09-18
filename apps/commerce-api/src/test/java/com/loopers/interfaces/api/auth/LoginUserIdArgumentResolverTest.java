package com.loopers.interfaces.api.auth;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.NativeWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginUserIdArgumentResolverTest {

    private final LoginUserIdArgumentResolver resolver = new LoginUserIdArgumentResolver();

    @DisplayName("X-USER-ID 헤더를 해석할 때, ")
    @Nested
    class ResolveArgument {
        @DisplayName("유효한 값이면, Long으로 변환된다.")
        @Test
        void returnsUserId_whenHeaderIsValid() {
            // arrange
            NativeWebRequest request = mock(NativeWebRequest.class);
            when(request.getHeader("X-USER-ID")).thenReturn("1");

            // act
            Object result = resolver.resolveArgument(null, null, request, null);

            // assert
            assertThat(result).isEqualTo(1L);
        }

        @DisplayName("헤더가 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenHeaderIsMissing() {
            // arrange
            NativeWebRequest request = mock(NativeWebRequest.class);
            when(request.getHeader("X-USER-ID")).thenReturn(null);

            // act
            CoreException result = assertThrows(CoreException.class,
                () -> resolver.resolveArgument(null, null, request, null));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("숫자가 아니면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenHeaderIsNotNumeric() {
            // arrange
            NativeWebRequest request = mock(NativeWebRequest.class);
            when(request.getHeader("X-USER-ID")).thenReturn("abc");

            // act
            CoreException result = assertThrows(CoreException.class,
                () -> resolver.resolveArgument(null, null, request, null));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
