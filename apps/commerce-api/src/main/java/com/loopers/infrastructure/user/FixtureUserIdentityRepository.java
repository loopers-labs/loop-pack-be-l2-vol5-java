package com.loopers.infrastructure.user;

import com.loopers.domain.user.UserIdentity;
import com.loopers.domain.user.UserIdentityRepository;
import com.loopers.domain.user.UserRole;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class FixtureUserIdentityRepository implements UserIdentityRepository {

    private static final Map<String, UserIdentity> IDENTITIES = Map.of(
        "alice", new UserIdentity(1L, UserRole.CUSTOMER),
        "bob", new UserIdentity(2L, UserRole.CUSTOMER),
        "admin", new UserIdentity(3L, UserRole.ADMIN)
    );

    @Override
    public Optional<UserIdentity> findByExternalId(String externalId) {
        return Optional.ofNullable(IDENTITIES.get(externalId));
    }

    @Override
    public Optional<String> findExternalId(long userId) {
        return IDENTITIES.entrySet().stream()
            .filter(entry -> entry.getValue().userId() == userId)
            .map(Map.Entry::getKey)
            .findFirst();
    }

    public Collection<UserIdentity> identities() {
        return List.copyOf(IDENTITIES.values());
    }
}
