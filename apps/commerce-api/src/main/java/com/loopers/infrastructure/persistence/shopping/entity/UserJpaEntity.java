package com.loopers.infrastructure.persistence.shopping.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
// 사용자 JPA 엔티티
public class UserJpaEntity {
    @Id
    private Long id;

    protected UserJpaEntity() {}

    UserJpaEntity(long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
