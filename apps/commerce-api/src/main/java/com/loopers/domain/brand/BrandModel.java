package com.loopers.domain.brand;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "brands")
public class BrandModel extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    protected BrandModel() {
    }

    public BrandModel(String name) {
        this.name = validateName(name);
    }

    public String getName() {
        return name;
    }

    public void update(String name) {
        this.name = validateName(name);
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 비어있을 수 없습니다.");
        }
        return name;
    }
}
