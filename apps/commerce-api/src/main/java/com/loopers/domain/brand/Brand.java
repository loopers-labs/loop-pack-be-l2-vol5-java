package com.loopers.domain.brand;

public final class Brand {
    private final BrandId id;
    private String name;
    private boolean deleted;

    private Brand(BrandId id, String name, boolean deleted) {
        if (name == null || name.isBlank()) {
            throw new com.loopers.domain.common.InvalidValueException("브랜드 이름은 필수입니다.");
        }
        if (name.length() > 100) {
            throw new com.loopers.domain.common.InvalidValueException("브랜드 이름은 100자 이하여야 합니다.");
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
            throw new com.loopers.domain.common.InvalidValueException("복원할 브랜드 ID는 필수입니다.");
        }
        return new Brand(id, name, deleted);
    }

    public void changeName(String name) {
        if (deleted) { throw new com.loopers.domain.common.RuleViolationException("삭제된 브랜드는 수정할 수 없습니다."); }
        Brand validated = create(name);
        this.name = validated.name;
    }

    public void delete() { deleted = true; }

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
