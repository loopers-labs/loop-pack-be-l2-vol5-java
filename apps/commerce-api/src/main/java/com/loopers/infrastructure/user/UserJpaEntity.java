package com.loopers.infrastructure.user;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class UserJpaEntity {
    @Id private Long id;

    protected UserJpaEntity() {}

    public UserJpaEntity(Long id) {
        this.id = id;
    }
}
