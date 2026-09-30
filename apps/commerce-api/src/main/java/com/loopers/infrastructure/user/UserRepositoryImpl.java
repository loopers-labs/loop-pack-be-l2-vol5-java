package com.loopers.infrastructure.user;

import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpaRepository;

    @Override
    public Optional<UserModel> find(Long id) {
        return userJpaRepository.findById(id);
    }

    @Override
    public Optional<UserModel> findForUpdate(Long id) {
        return userJpaRepository.findForUpdate(id);
    }

    @Override
    public int chargePoint(Long id, long amount) {
        return userJpaRepository.chargePoint(id, amount);
    }
}
