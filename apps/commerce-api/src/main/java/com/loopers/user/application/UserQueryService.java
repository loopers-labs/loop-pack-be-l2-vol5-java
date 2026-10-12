package com.loopers.user.application;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.user.application.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class UserQueryService {
    private final UserPort userPort;

    /**
     * USR-01: 요청자로 식별할 사용자가 존재하는지 확인한다. 없으면 UNAUTHORIZED.
     */
    @Transactional(readOnly = true)
    public void checkExists(Long userId) {
        if (!userPort.existsById(userId)) {
            throw new CoreException(ErrorType.UNAUTHORIZED);
        }
    }
}
