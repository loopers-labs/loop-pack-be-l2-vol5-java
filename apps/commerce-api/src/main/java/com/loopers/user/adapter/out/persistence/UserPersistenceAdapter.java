package com.loopers.user.adapter.out.persistence;

import com.loopers.user.application.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class UserPersistenceAdapter implements UserPort {
    private final UserJpaRepository userJpaRepository;

    @Override
    public boolean existsById(Long id) {
        return userJpaRepository.existsById(id);
    }
}
