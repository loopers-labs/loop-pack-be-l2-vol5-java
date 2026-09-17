package com.loopers.domain.user;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 좋아요·포인트·주문의 소유 관계를 식별하는 기준.
 * 이번 범위에서 User 생성 API 는 다루지 않으며 테스트 fixture 로 준비한다.
 */
@Entity
@Table(name = "users")
public class UserModel extends BaseEntity {

    protected UserModel() {}

    public static UserModel create() {
        return new UserModel();
    }
}
