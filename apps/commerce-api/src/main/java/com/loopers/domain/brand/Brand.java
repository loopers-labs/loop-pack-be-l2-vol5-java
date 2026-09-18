package com.loopers.domain.brand;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.Objects;

@Entity
@Table(name = "brand")
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private BrandName name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    @Column(name = "deleted_at")
    private ZonedDateTime deletedAt;

    protected Brand() {
    }

    public Brand(String name) {
        this.name = new BrandName(name);
    }

    public String getName() {
        return name.value();
    }

    public Long getId() {
        return id;
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    public ZonedDateTime getUpdatedAt() {
        return updatedAt;
    }

    @PrePersist
    private void prePersist() {
        ZonedDateTime now = ZonedDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = ZonedDateTime.now();
    }

    public void rename(String newName) {
        if (isDeleted()) {
            throw new BrandStateException(BrandStateException.Reason.DELETED_BRAND);
        }
        this.name = new BrandName(newName);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public ZonedDateTime getDeletedAt() {
        return deletedAt;
    }

    void delete(ZonedDateTime deletedAt) {
        if (isDeleted()) {
            return;
        }
        this.deletedAt = Objects.requireNonNull(deletedAt, "deletedAt");
    }
}
