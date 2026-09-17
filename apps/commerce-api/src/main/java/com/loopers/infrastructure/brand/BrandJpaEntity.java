package com.loopers.infrastructure.brand;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "brands")
public class BrandJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean deleted;

    protected BrandJpaEntity() {
    }

    BrandJpaEntity(String name, boolean deleted) {
        update(name, deleted);
    }

    void update(String name, boolean deleted) {
        this.name = name;
        this.deleted = deleted;
    }

    Long getId() {
        return id;
    }

    String getName() {
        return name;
    }

    boolean isDeleted() {
        return deleted;
    }
}
