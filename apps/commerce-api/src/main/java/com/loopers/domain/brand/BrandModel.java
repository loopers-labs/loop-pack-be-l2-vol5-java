package com.loopers.domain.brand;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "brands")
public class BrandModel extends BaseEntity {

    private static final int NAME_MAX_LENGTH = 50;
    private static final int DESCRIPTION_MAX_LENGTH = 200;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
    private String description;

    protected BrandModel() {}

    public BrandModel(String name, String description) {
        this.name = validName(name);
        this.description = validDescription(description);
    }

    /**
     * BRD-01: 이름·설명을 함께 바꾼다. 하나라도 유효하지 않으면 아무것도 바꾸지 않는다.
     */
    public void update(String name, String description) {
        String newName = validName(name);
        String newDescription = validDescription(description);
        this.name = newName;
        this.description = newDescription;
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 비어 있을 수 없습니다.");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return trimmed;
    }

    private static String validDescription(String description) {
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 설명은 " + DESCRIPTION_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}
