package com.loopers.domain.user;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * AG-01 사용자 (TB-01 user). 관리자는 별개 대상이 아니라 권한 속성이다 (INV-15).
 * 런타임에 쓰는 FR 이 없고 fixture 로만 채워진다 (DR-12).
 */
@Entity
@Table(name = "user")
public class UserModel extends BaseEntity {

    @Column(name = "is_admin", nullable = false)
    private boolean admin;

    protected UserModel() {}

    public UserModel(boolean admin) {
        this.admin = admin;
    }

    public boolean isAdmin() {
        return admin;
    }
}
