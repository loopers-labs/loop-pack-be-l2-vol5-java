package com.loopers.domain.brand;

public final class Brand {
    private final BrandId id;
    private final String name;
    private final boolean deleted;

    private Brand(BrandId id, String name, boolean deleted) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("브랜드 이름은 필수입니다.");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException("브랜드 이름은 100자 이하여야 합니다.");
        }
        this.id = id;
        this.name = name;
        this.deleted = deleted;
    }

    public static Brand create(String name) {
        return new Brand(null, name, false);
    }

    public static Brand restore(BrandId id, String name, boolean deleted) {
        if (id == null) {
            throw new IllegalArgumentException("복원할 브랜드 ID는 필수입니다.");
        }
        return new Brand(id, name, deleted);
    }

    public BrandId getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
