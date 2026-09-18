package com.loopers.infrastructure.user;

import com.loopers.domain.user.UserIdentity;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

@Component
public class FixtureUserInitializer implements ApplicationRunner {

    private final FixtureUserIdentityRepository identityRepository;
    private final UserJpaRepository userJpaRepository;

    public FixtureUserInitializer(FixtureUserIdentityRepository identityRepository, UserJpaRepository userJpaRepository) {
        this.identityRepository = identityRepository;
        this.userJpaRepository = userJpaRepository;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void run(ApplicationArguments arguments) {
        initialize();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void initialize() {
        identityRepository.identities().stream()
            .sorted(Comparator.comparingLong(UserIdentity::userId))
            .forEach(identity -> userJpaRepository.insertIfMissing(identity.userId()));
    }
}
