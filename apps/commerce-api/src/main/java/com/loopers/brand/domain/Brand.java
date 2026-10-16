package com.loopers.brand.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;
import jakarta.persistence.Table;

@Entity
@Table(name = "brand", uniqueConstraints = @UniqueConstraint(
    name = "uk_brand_active_name", columnNames = "active_name_key"))
@Check(name = "ck_brand_name", constraints = "octet_length(convert(name using utf16)) between 2 and 100"
    + " and ascii(left(name, 1)) > 32 and ascii(right(name, 1)) > 32")
public class Brand extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String name;

    // Deleted names are reusable; binary keys preserve Java String.equals semantics.
    @Column(name = "active_name_key", insertable = false, updatable = false,
        columnDefinition = "varbinary(200) generated always as"
            + " (case when deleted_at is null then convert(name using binary) else null end) virtual")
    private byte[] activeNameKey;

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
