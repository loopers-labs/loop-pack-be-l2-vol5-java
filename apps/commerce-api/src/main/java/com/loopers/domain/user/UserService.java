package com.loopers.domain.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class UserService {

    private final UserRepository userRepository;

    /** 대상 없음 예외를 던지지 않는다. 식별 실패의 의미는 요청자를 확인하는 쪽이 정한다 (설계 2.6). */
    @Transactional(readOnly = true)
    public boolean exists(Long userId) {
        return userId != null && userRepository.exists(userId);
    }
}
