package com.loopers.interfaces.api;

import com.loopers.application.user.UserIdentificationService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

@Component
public class CustomerIdentity {
    private final UserIdentificationService users;
    public CustomerIdentity(UserIdentificationService users) { this.users = users; }
    public long require(String value) {
        if (value == null) { throw new CoreException(ErrorType.UNAUTHORIZED); }
        long id;
        try { id = Long.parseLong(value); }
        catch (NumberFormatException e) { throw new CoreException(ErrorType.BAD_REQUEST); }
        if (!users.exists(id)) { throw new CoreException(ErrorType.UNAUTHORIZED); }
        return id;
    }
}
