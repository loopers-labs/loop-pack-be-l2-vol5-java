package com.loopers.application.user;

public class UserResolutionException extends RuntimeException {

    public enum Reason {
        INVALID_USER_ID,
        USER_NOT_FOUND,
        ADMIN_REQUIRED
    }

    private final Reason reason;

    UserResolutionException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public static UserResolutionException userNotFound() {
        return new UserResolutionException(Reason.USER_NOT_FOUND);
    }
}
