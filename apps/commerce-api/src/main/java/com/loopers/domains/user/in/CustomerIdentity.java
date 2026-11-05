package com.loopers.domains.user.in;

import com.loopers.common.in.error.CoreException;
import com.loopers.common.in.error.ErrorType;
import com.loopers.domains.user.app.UserIdentificationService;
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
