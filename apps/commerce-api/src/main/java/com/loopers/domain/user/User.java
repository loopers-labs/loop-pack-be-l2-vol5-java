package com.loopers.domain.user;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 존재 확인 전용 최소 엔티티. 식별자 외의 속성과 규칙은 갖지 않는다.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {
}
