package com.loopers.infrastructure.persistence.shopping.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import com.loopers.infrastructure.persistence.shopping.entity.UserJpaEntity;

// 사용자 Spring Data JPA 저장소
public interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {}
