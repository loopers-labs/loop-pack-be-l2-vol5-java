package com.loopers.infrastructure.user;

import com.loopers.domain.user.UserIdentity;
import com.loopers.domain.user.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureUserIdentityRepositoryTest {

    private final FixtureUserIdentityRepository repository = new FixtureUserIdentityRepository();

    @DisplayName("IDENTITY-07: 확정한 fixture의 사용자 ID와 권한을 조회한다.")
    @ParameterizedTest
    @CsvSource({"alice, 1, CUSTOMER", "bob, 2, CUSTOMER", "admin, 3, ADMIN"})
    void findsConfirmedFixtureIdentity(String externalId, long userId, UserRole role) {
        assertThat(repository.findByExternalId(externalId))
            .contains(new UserIdentity(userId, role));
        assertThat(repository.findExternalId(userId)).contains(externalId);
    }

    @DisplayName("IDENTITY-08: 미등록 식별값과 내부 ID 문자열은 사용자로 연결하지 않는다.")
    @ParameterizedTest
    @ValueSource(strings = {"unknown", "1", "2", "3"})
    void doesNotResolveUnknownOrInternalUserId(String externalId) {
        assertThat(repository.findByExternalId(externalId)).isEmpty();
    }

    @DisplayName("IDENTITY-09: 대소문자와 앞뒤 공백이 다르면 fixture와 일치하지 않는다.")
    @ParameterizedTest
    @ValueSource(strings = {"Alice", "ADMIN", " alice", "alice ", " admin", "admin "})
    void matchesExternalIdExactly(String externalId) {
        assertThat(repository.findByExternalId(externalId)).isEmpty();
    }
}
