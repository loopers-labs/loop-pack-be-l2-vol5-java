package com.loopers.user.adapter.out.persistence;

import com.loopers.user.domain.UserModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserJpaRepository extends JpaRepository<UserModel, Long> {}
