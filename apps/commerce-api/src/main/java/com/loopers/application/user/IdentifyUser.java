package com.loopers.application.user;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IdentifyUser {
    private final UserLookup users;

    public long require(Long userId) {
        if (userId == null || userId <= 0) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
        if (!users.exists(userId)) {
            throw new CoreException(ErrorType.USER_NOT_FOUND);
        }
        return userId;
    }
}
