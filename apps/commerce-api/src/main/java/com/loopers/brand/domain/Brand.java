package com.loopers.brand.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "brand")
public class Brand extends BaseEntity {

    private String name;

    protected Brand() {
    }

    public Brand(String name) {
        this.name = validateName(name);
    }

    public String getName() {
        return name;
    }

    public boolean isDeleted() {
        return getDeletedAt() != null;
    }

    public void update(String name) {
        ensureActive();
        this.name = validateName(name);
    }

    @Override
    public void delete() {
        ensureActive();
        super.delete();
    }

    private static String validateName(String name) {
        if (name == null) {
            throw new CoreException(ErrorCode.INVALID_BRAND_NAME);
        }
        String trimmedName = name.trim();
        if (trimmedName.isEmpty() || trimmedName.length() > 50) {
            throw new CoreException(ErrorCode.INVALID_BRAND_NAME);
        }
        return trimmedName;
    }

    private void ensureActive() {
        if (isDeleted()) {
            throw new CoreException(ErrorCode.BRAND_NOT_FOUND);
        }
    }
}
