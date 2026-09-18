package com.loopers.domain.user;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 실습용 고객. 식별자만 가진 최소 엔티티이며 테스트 데이터로 준비한다 (설계 2.6).
 * MySQL 키워드와 겹치지 않도록 테이블 이름은 users 로 둔다.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {
}
