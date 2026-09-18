package com.loopers.application.user;

import com.loopers.domain.user.UserIdentity;
import com.loopers.domain.user.UserIdentityRepository;
import com.loopers.domain.user.UserRole;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class UserResolverTest {

    @DisplayName("IDENTITY-01: 등록된 외부 식별 문자열로 내부 사용자 ID와 권한을 얻는다.")
    @Test
    void resolvesRegisteredUserIdentity() {
        UserIdentity identity = new UserIdentity(42L, UserRole.CUSTOMER);
        UserIdentityRepository repository = repository(externalId ->
            "fixture-user".equals(externalId) ? Optional.of(identity) : Optional.empty());
        UserResolver resolver = new UserResolver(repository);

        UserIdentity resolved = resolver.resolve("fixture-user");

        assertThat(resolved).isEqualTo(identity);
    }

    @DisplayName("IDENTITY-02: 매핑되지 않은 사용자 식별값은 거절한다.")
    @Test
    void rejectsUnregisteredUser() {
        UserResolver resolver = new UserResolver(repository(externalId -> Optional.empty()));

        assertResolutionRejected(() -> resolver.resolve("unknown-user"), UserResolutionException.Reason.USER_NOT_FOUND);
    }

    @DisplayName("IDENTITY-03: 누락·빈 값·공백만 있는 식별값은 조회 전에 거절한다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void rejectsMissingOrBlankUserIdBeforeLookup(String externalId) {
        AtomicInteger lookupCount = new AtomicInteger();
        UserResolver resolver = new UserResolver(repository(value -> {
            lookupCount.incrementAndGet();
            return Optional.empty();
        }));

        assertAll(
            () -> assertResolutionRejected(() -> resolver.resolve(externalId), UserResolutionException.Reason.INVALID_USER_ID),
            () -> assertThat(lookupCount).hasValue(0)
        );
    }

    @DisplayName("IDENTITY-04: 관리자 권한을 가진 사용자는 관리자 검사에 통과한다.")
    @Test
    void allowsAdministrator() {
        UserIdentity administrator = new UserIdentity(7L, UserRole.ADMIN);
        UserResolver resolver = new UserResolver(repository(externalId -> Optional.of(administrator)));

        UserIdentity resolved = resolver.requireAdmin("fixture-admin");

        assertThat(resolved).isEqualTo(administrator);
    }

    @DisplayName("IDENTITY-05: 일반 고객은 관리자 검사에서 거절한다.")
    @Test
    void rejectsCustomerForAdministratorAccess() {
        UserIdentity customer = new UserIdentity(42L, UserRole.CUSTOMER);
        UserResolver resolver = new UserResolver(repository(externalId -> Optional.of(customer)));

        assertResolutionRejected(() -> resolver.requireAdmin("fixture-user"), UserResolutionException.Reason.ADMIN_REQUIRED);
    }

    @DisplayName("IDENTITY-06: 관리자 검사에서도 미등록 사용자 오류를 구분한다.")
    @Test
    void rejectsUnregisteredUserBeforeCheckingAdminRole() {
        UserResolver resolver = new UserResolver(repository(externalId -> Optional.empty()));

        assertResolutionRejected(() -> resolver.requireAdmin("unknown-user"), UserResolutionException.Reason.USER_NOT_FOUND);
    }

    @DisplayName("IDENTITY-06: 관리자 검사에서도 누락된 사용자 식별값을 거절한다.")
    @Test
    void rejectsMissingUserIdBeforeCheckingAdminRole() {
        UserResolver resolver = new UserResolver(repository(externalId -> Optional.empty()));

        assertResolutionRejected(() -> resolver.requireAdmin(null), UserResolutionException.Reason.INVALID_USER_ID);
    }

    private void assertResolutionRejected(ThrowingCallable action, UserResolutionException.Reason expectedReason) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(UserResolutionException.class,
                error -> assertThat(error.getReason()).isEqualTo(expectedReason));
    }

    private UserIdentityRepository repository(Function<String, Optional<UserIdentity>> lookup) {
        return new UserIdentityRepository() {
            @Override
            public Optional<UserIdentity> findByExternalId(String externalId) {
                return lookup.apply(externalId);
            }

            @Override
            public Optional<String> findExternalId(long userId) {
                throw new AssertionError("이 테스트는 내부 ID의 역조회를 요청하지 않는다.");
            }
        };
    }
}
