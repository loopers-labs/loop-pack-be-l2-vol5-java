package com.loopers.interfaces.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.loopers.application.shopping.query.UserQueryDao;
import com.loopers.application.shopping.query.UserView;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class XUserIdArgumentResolverTest {
    private final UserQueryDao userQueryDao = mock(UserQueryDao.class);
    private final XUserIdArgumentResolver resolver = new XUserIdArgumentResolver(userQueryDao);

    @DisplayName("파라미터 지원 여부를 확인할 때")
    @Nested
    class SupportsParameter {
        @DisplayName("XUserId가 붙은 long과 Long 파라미터만 지원한다")
        @Test
        void supportsOnlyAnnotatedLongParameters() throws Exception {
            // arrange / act / assert
            assertThat(resolver.supportsParameter(parameter("primitive", long.class))).isTrue();
            assertThat(resolver.supportsParameter(parameter("boxed", Long.class))).isTrue();
            assertThat(resolver.supportsParameter(parameter("unannotated", long.class))).isFalse();
            assertThat(resolver.supportsParameter(parameter("text", String.class))).isFalse();
            verifyNoInteractions(userQueryDao);
        }
    }

    @DisplayName("유효한 사용자 헤더를 처리할 때")
    @Nested
    class ValidHeader {
        @DisplayName("사용자 존재를 확인한 뒤 양의 long ID를 반환한다")
        @ParameterizedTest
        @ValueSource(longs = {1L, 2L, Long.MAX_VALUE})
        void resolvesExistingUser(long id) throws Exception {
            // arrange
            given(userQueryDao.findById(id)).willReturn(Optional.of(new UserView(id)));

            // act
            Object userId = resolve("X-USER-ID", Long.toString(id));

            // assert
            assertThat(userId).isEqualTo(id);
            verify(userQueryDao).findById(id);
            verifyNoMoreInteractions(userQueryDao);
        }

        @DisplayName("헤더 이름은 대소문자를 구분하지 않는다")
        @Test
        void resolvesLowercaseHeaderName() throws Exception {
            // arrange
            given(userQueryDao.findById(2L)).willReturn(Optional.of(new UserView(2L)));

            // act
            Object userId = resolve("x-user-id", "2");

            // assert
            assertThat(userId).isEqualTo(2L);
            verify(userQueryDao).findById(2L);
        }

        @DisplayName("없는 사용자는 USER_NOT_FOUND로 거절하고 조회 외의 처리를 하지 않는다")
        @Test
        void rejectsMissingUser() {
            // arrange
            given(userQueryDao.findById(3L)).willReturn(Optional.empty());

            // act / assert
            assertThatThrownBy(() -> resolve("X-USER-ID", "3"))
                .isInstanceOfSatisfying(ApplicationException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ApplicationErrorCode.USER_NOT_FOUND));
            verify(userQueryDao).findById(3L);
            verifyNoMoreInteractions(userQueryDao);
        }
    }

    @DisplayName("잘못된 사용자 헤더를 처리할 때")
    @Nested
    class InvalidHeader {
        @DisplayName("누락되거나 빈 헤더는 DB 조회 없이 필수 입력 오류로 거절한다")
        @ParameterizedTest
        @NullAndEmptySource
        void rejectsMissingHeader(String value) {
            // arrange / act / assert
            assertBadRequest(value, "X-USER-ID는 필수입니다.");
        }

        @DisplayName("형식 또는 범위 오류는 DB 조회 없이 거절한다")
        @ParameterizedTest
        @ValueSource(strings = {"abc", "1.0", " 1", "1 ", " ", "0", "-1", "9223372036854775808"})
        void rejectsInvalidHeader(String value) {
            // arrange / act / assert
            assertBadRequest(value, "X-USER-ID는 양의 정수여야 합니다.");
        }
    }

    private void assertBadRequest(String value, String message) {
        assertThatThrownBy(() -> resolve("X-USER-ID", value))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getMessage()).isEqualTo(message);
            });
        verifyNoInteractions(userQueryDao);
    }

    private Object resolve(String headerName, String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader(headerName, headerValue);
        }
        return resolver.resolveArgument(parameter("primitive", long.class), null, new ServletWebRequest(request), null);
    }

    private MethodParameter parameter(String methodName, Class<?> parameterType) throws Exception {
        Method method = Parameters.class.getDeclaredMethod(methodName, parameterType);
        return new MethodParameter(method, 0);
    }

    private static class Parameters {
        void primitive(@XUserId long userId) {}

        void boxed(@XUserId Long userId) {}

        void unannotated(long userId) {}

        void text(@XUserId String userId) {}
    }
}
