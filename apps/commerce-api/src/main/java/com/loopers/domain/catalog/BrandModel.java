package com.loopers.domain.catalog;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * AG-02 브랜드 (TB-02 brand). 상태 ST-01 은 deletedAt 의 NULL 여부 (DR-16).
 * 전이: (없음) → ACTIVE (생성), ACTIVE → DELETED (delete()).
 */
@Entity
@Table(name = "brand")
public class BrandModel extends BaseEntity {
    public static final int NAME_MAX_LENGTH = 100;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    protected BrandModel() {}

    public BrandModel(String name) {
        this.name = validateName(name);
    }

    /** INV-14 브랜드 정보는 유효 범위 안 (ASM-03: 이름 1~100자). ER-17 INVALID_BRAND. */
    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.INVALID_BRAND, "브랜드 이름은 비어 있을 수 없습니다.");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.INVALID_BRAND, "브랜드 이름은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return name;
    }

    public void update(String name) {
        this.name = validateName(name);
    }

    public boolean isDeleted() {
        return getDeletedAt() != null;
    }

    public String getName() {
        return name;
    }
}
