package com.loopers.application.user;

import com.loopers.domain.user.UserIdentity;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.user.UserJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class UserIdentityWiringTest {

    @DisplayName("IDENTITY-10: Spring이 단일 fixture를 연결하고 식별·권한 규칙을 유지한다.")
    @Test
    void wiresFixtureWithoutChangingIdentityOrRoleRules() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(UserJpaRepository.class, () -> mock(UserJpaRepository.class));
            context.scan("com.loopers.application.user", "com.loopers.infrastructure.user");
            context.refresh();
            UserResolver resolver = context.getBean(UserResolver.class);

            assertThat(resolver.resolve("alice")).isEqualTo(new UserIdentity(1L, UserRole.CUSTOMER));
            assertThat(resolver.resolve("bob")).isEqualTo(new UserIdentity(2L, UserRole.CUSTOMER));
            assertThat(resolver.requireAdmin("admin")).isEqualTo(new UserIdentity(3L, UserRole.ADMIN));
            assertThat(resolver.findExternalId(1L)).contains("alice");
            assertThat(resolver.findExternalId(2L)).contains("bob");
            assertThat(resolver.findExternalId(3L)).contains("admin");
            assertThat(resolver.findExternalId(Long.MAX_VALUE)).isEmpty();

            assertThatThrownBy(() -> resolver.requireAdmin("alice"))
                .isInstanceOfSatisfying(UserResolutionException.class,
                    error -> assertThat(error.getReason())
                        .isEqualTo(UserResolutionException.Reason.ADMIN_REQUIRED));

            for (String externalId : new String[] {"Alice", "alice ", " admin", "3", "unknown"}) {
                assertThatThrownBy(() -> resolver.resolve(externalId))
                    .isInstanceOfSatisfying(UserResolutionException.class,
                        error -> assertThat(error.getReason())
                            .isEqualTo(UserResolutionException.Reason.USER_NOT_FOUND));
            }
        }
    }
}
