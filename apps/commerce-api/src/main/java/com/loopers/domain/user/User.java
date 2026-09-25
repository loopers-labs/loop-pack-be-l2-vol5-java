package com.loopers.domain.user;

public class User {

    private final Long id;

    private User(Long id) {
        this.id = id;
    }

    public static User create() {
        return new User(null);
    }

    public static User reconstitute(Long id) {
        return new User(id);
    }

    public Long getId() {
        return id;
    }
}
