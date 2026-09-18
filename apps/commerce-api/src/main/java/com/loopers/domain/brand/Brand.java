package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.Getter;

@Getter
public class Brand {
    private final Long id;
    private String name;
    private boolean deleted;

    private Brand(Long id, String name, boolean deleted) {
        this.id = id;
        this.name = validName(name);
        this.deleted = deleted;
    }

    public static Brand create(String name) { return new Brand(null, name, false); }
    public static Brand restore(long id, String name, boolean deleted) { return new Brand(id, name, deleted); }

    public void rename(String name) {
        requireActive();
        this.name = validName(name);
    }

    public void delete(boolean hasActiveProducts) {
        if (deleted) { return; }
        if (hasActiveProducts) { throw new CoreException(ErrorType.BRAND_HAS_ACTIVE_PRODUCTS); }
        deleted = true;
    }

    public void requireActive() {
        if (deleted) { throw new CoreException(ErrorType.BRAND_NOT_FOUND); }
    }

    private static String validName(String name) {
        if (name == null || name.strip().isEmpty() || name.strip().length() > 50) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
        return name.strip();
    }
}
