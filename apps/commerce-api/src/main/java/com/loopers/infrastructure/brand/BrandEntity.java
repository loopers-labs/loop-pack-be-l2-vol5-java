package com.loopers.infrastructure.brand;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.brand.Brand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "brand")
class BrandEntity extends BaseEntity {

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "description", length = 200)
    private String description;

    protected BrandEntity() {}

    static BrandEntity from(Brand brand) {
        BrandEntity entity = new BrandEntity();
        entity.name = brand.getName();
        entity.description = brand.getDescription();
        return entity;
    }

    void apply(Brand brand) {
        this.name = brand.getName();
        this.description = brand.getDescription();
        if (brand.isDeleted()) {
            delete();
        }
    }

    Brand toDomain() {
        return Brand.restore(getId(), name, description, getDeletedAt() != null);
    }
}
