package com.loopers.domain.user;

import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public User getUser(Long id) {
        return userRepository.find(id)
            .orElseThrow(() -> new DomainException(DomainErrorType.NOT_FOUND, "[userId = " + id + "] 사용자를 찾을 수 없습니다."));
    }

    @Transactional
    public long charge(Long userId, Long amount) {
        User user = getUser(userId);
        user.charge(amount);
        return user.getBalance();
    }
}
