package com.loopers.infrastructure.brand;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.brand.Brand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "brands")
public class BrandJpaEntity extends BaseEntity {
    @Column(nullable = false, length = 50) private String name;
    protected BrandJpaEntity() {}
    public BrandJpaEntity(Brand brand) { update(brand); }
    public void update(Brand brand) {
        name = brand.getName();
        if (brand.isDeleted()) { delete(); }
    }
    public Brand toDomain() { return Brand.restore(getId(), name, getDeletedAt() != null); }
}
