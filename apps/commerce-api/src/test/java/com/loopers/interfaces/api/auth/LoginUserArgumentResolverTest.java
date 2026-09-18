package com.loopers.interfaces.api.auth;

import com.loopers.application.user.UserFacade;
import com.loopers.domain.user.FakeUserRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginUserArgumentResolverTest {

    private FakeUserRepository userRepository;
    private LoginUserArgumentResolver resolver;

    @BeforeEach
    void setUp() {
        userRepository = new FakeUserRepository();
        resolver = new LoginUserArgumentResolver(new UserFacade(new UserService(userRepository)));
    }

    private LoginUser resolve(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader(LoginUserArgumentResolver.USER_ID_HEADER, header);
        }
        return resolver.resolveArgument(null, null, new ServletWebRequest(request), null);
    }

    @DisplayName("존재하는 사용자의 식별자면, 그 식별자를 가진 요청자를 돌려준다.")
    @Test
    void resolvesLoginUser_whenUserExists() {
        // arrange
        User user = userRepository.save(new User());

        // act
        LoginUser loginUser = resolve(String.valueOf(user.getId()));

        // assert
        assertThat(loginUser.id()).isEqualTo(user.getId());
    }

    @DisplayName("헤더가 없거나, 숫자가 아니거나, 없는 사용자면 모두 UNAUTHENTICATED 예외가 발생한다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "abc", "1.5", "999"})
    void throwsUnauthenticated_whenRequesterCannotBeIdentified(String header) {
        // act
        CoreException result = assertThrows(CoreException.class, () -> resolve(header));

        // assert
        assertThat(result.getErrorCode()).isEqualTo(ErrorType.UNAUTHENTICATED);
    }
}
