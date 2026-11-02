package com.loopers.user.app;

import com.loopers.user.app.port.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserIdentificationService {
    private final UserRepository repository;

    public UserIdentificationService(UserRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public boolean exists(long userId) {
        return repository.existsById(userId);
    }
}
