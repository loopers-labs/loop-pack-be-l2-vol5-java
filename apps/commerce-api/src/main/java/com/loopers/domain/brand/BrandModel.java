package com.loopers.domain.brand;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "brand")
public class BrandModel extends BaseEntity {

    // 글자수 상한은 DB 컬럼 크기와 동일하게 맞춘다 (Product name과 동일한 원칙).
    // name·category: VARCHAR 기본 길이(255)에 맞춤. description: 자유 서술이라 여유 있게 500으로 직접 지정.
    private static final int NAME_MAX_LENGTH = 255;
    private static final int CATEGORY_MAX_LENGTH = 255;
    private static final int DESCRIPTION_MAX_LENGTH = 500;

    @Column(length = NAME_MAX_LENGTH)
    private String name;
    @Column(length = DESCRIPTION_MAX_LENGTH)
    private String description;
    @Column(length = CATEGORY_MAX_LENGTH)
    private String category;

    protected BrandModel() {}

    public BrandModel(String name, String description, String category) {
        update(name, description, category);
    }

    /**
     * 이름·설명·카테고리를 함께 갱신한다. 생성자와 검증 규칙을 공유한다 —
     * 생성 시 지켜야 할 불변식과 수정 시 지켜야 할 불변식이 다를 이유가 없다.
     */
    public void update(String name, String description, String category) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "이름은 비어있을 수 없습니다.");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "이름은 " + NAME_MAX_LENGTH + "자를 초과할 수 없습니다.");
        }
        if (description == null || description.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "설명은 비어있을 수 없습니다.");
        }
        if (description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "설명은 " + DESCRIPTION_MAX_LENGTH + "자를 초과할 수 없습니다.");
        }
        if (category == null || category.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카테고리는 비어있을 수 없습니다.");
        }
        if (category.length() > CATEGORY_MAX_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카테고리는 " + CATEGORY_MAX_LENGTH + "자를 초과할 수 없습니다.");
        }

        this.name = name;
        this.description = description;
        this.category = category;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }
}
