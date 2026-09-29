package com.loopers.domain.brand;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;

public class Brand {

    private final Long id;
    private BrandName name;
    private BrandDescription description;
    private boolean deleted;

    private Brand(Long id, BrandName name, BrandDescription description, boolean deleted) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.deleted = deleted;
    }

    public static Brand register(String name, String description) {
        return new Brand(null, BrandName.of(name), BrandDescription.of(description), false);
    }

    public static Brand restore(Long id, String name, String description, boolean deleted) {
        return new Brand(id, BrandName.of(name), BrandDescription.of(description), deleted);
    }

    public void update(String name, String description) {
        if (deleted) {
            throw new DomainException(DomainError.BRAND_ALREADY_DELETED);
        }
        BrandName newName = BrandName.of(name);
        BrandDescription newDescription = BrandDescription.of(description);
        this.name = newName;
        this.description = newDescription;
    }

    public void delete() {
        this.deleted = true;
    }

    public Long getId() {
        return id;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public String getName() {
        return name.value();
    }

    public String getDescription() {
        return description.value();
    }
}
