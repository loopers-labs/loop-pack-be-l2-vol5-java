package com.loopers.user.infrastructure;

import com.loopers.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserJpaRepository extends JpaRepository<User, Long> {
}
