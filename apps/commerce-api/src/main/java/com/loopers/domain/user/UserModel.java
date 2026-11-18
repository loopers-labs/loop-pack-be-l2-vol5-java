package com.loopers.domain.user;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * AG-01 사용자 (TB-01 user). 관리자는 별개 대상이 아니라 권한 속성이다 (INV-15).
 * 테스트용 생성 API(POST /api/v1/users)가 포인트 계정과 함께 만든다 (DR-12, DR-59).
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
