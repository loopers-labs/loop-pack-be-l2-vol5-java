package com.loopers.domain.brand;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "brands",
    uniqueConstraints = @UniqueConstraint(name = "uk_brand_name", columnNames = "name")
)
public class Brand extends BaseEntity {

    private static final int MAX_NAME_LENGTH = 100;

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    protected Brand() {}

    private Brand(String name) {
        this.name = validateName(name);
    }

    public static Brand create(String name) {
        return new Brand(name);
    }

    public String getName() {
        return name;
    }

    public void rename(String name) {
        this.name = validateName(name);
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 비어있을 수 없습니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 100자 이하여야 합니다.");
        }
        return name;
    }
}
