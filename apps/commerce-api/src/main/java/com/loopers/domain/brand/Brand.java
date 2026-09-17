package com.loopers.domain.brand;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "brand")
public class Brand extends BaseEntity {

    private static final int MAX_NAME_LENGTH = 100;

    private String name;

    protected Brand() {}

    public Brand(String name) {
        validateName(name);
        this.name = name;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "브랜드 이름은 비어있을 수 없습니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "브랜드 이름은 " + MAX_NAME_LENGTH + "자 이하여야 합니다.");
        }
    }

    public String getName() {
        return name;
    }

    public void update(String name) {
        validateName(name);
        this.name = name;
    }
}
