package com.loopers.infrastructure.user;

import com.loopers.domain.user.UserModel;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserJpaRepository extends JpaRepository<UserModel, Long> {
    @Query("select u.id from UserModel u where u.id in :ids and u.deletedAt is null")
    List<Long> findActiveIds(@Param("ids") Collection<Long> ids);
}
