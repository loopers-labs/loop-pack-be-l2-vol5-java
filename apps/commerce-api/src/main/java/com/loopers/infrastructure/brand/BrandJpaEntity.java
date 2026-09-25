package com.loopers.infrastructure.brand;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "brands",
    uniqueConstraints = @UniqueConstraint(name = "uk_brand_name", columnNames = "name")
)
public class BrandJpaEntity extends BaseEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    private BrandJpaEntity(String name) {
        this.name = name;
    }

    public static BrandJpaEntity create(String name) {
        return new BrandJpaEntity(name);
    }

    public String getName() {
        return name;
    }

    public void rename(String name) {
        this.name = name;
    }
}
