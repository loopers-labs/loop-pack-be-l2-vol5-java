package com.loopers.domain.brand;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 브랜드 정보와 생명주기. 삭제 가능 여부는 전달받은 활성 상품 존재 여부로 스스로 판단한다.
 */
@Entity
@Table(name = "brand")
@Getter
public class BrandModel extends BaseEntity {

    /** [잠정] 브랜드명은 앞뒤 공백 제거 후 1~100자. */
    private static final int NAME_MAX_LENGTH = 100;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    protected BrandModel() {}

    private BrandModel(String name) {
        this.name = name;
    }

    public static BrandModel create(String name) {
        return new BrandModel(validateName(name));
    }

    public void updateName(String newName) {
        this.name = validateName(newName);
    }

    /**
     * 삭제되지 않은 상품이 하나라도 연결되어 있으면 재고가 0이어도 삭제하지 않는다.
     */
    public void delete(boolean hasActiveProducts) {
        if (hasActiveProducts) {
            throw new CoreException(ErrorType.BRAND_HAS_ACTIVE_PRODUCTS);
        }
        super.delete();
    }

    public boolean isDeleted() {
        return getDeletedAt() != null;
    }

    private static String validateName(String name) {
        if (name == null) {
            throw new CoreException(ErrorType.INVALID_BRAND_NAME);
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.INVALID_BRAND_NAME);
        }
        return trimmed;
    }
}
