package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;

public class Brand {

    private static final int MAX_NAME_LENGTH = 100;

    private final Long id;
    private final ZonedDateTime createdAt;
    private String name;
    private ZonedDateTime deletedAt;

    private Brand(Long id, String name, ZonedDateTime createdAt, ZonedDateTime deletedAt) {
        this.id = id;
        this.name = validateName(name);
        this.createdAt = createdAt;
        this.deletedAt = deletedAt;
    }

    public static Brand create(String name) {
        return new Brand(null, name, null, null);
    }

    public static Brand reconstitute(Long id, String name, ZonedDateTime createdAt, ZonedDateTime deletedAt) {
        return new Brand(id, name, createdAt, deletedAt);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    public ZonedDateTime getDeletedAt() {
        return deletedAt;
    }

    public void rename(String name) {
        this.name = validateName(name);
    }

    public void delete() {
        if (deletedAt == null) {
            deletedAt = ZonedDateTime.now();
        }
    }

    public static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 비어있을 수 없습니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 100자 이하여야 합니다.");
        }
        return name;
    }
}
