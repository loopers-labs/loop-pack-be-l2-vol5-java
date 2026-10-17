package com.loopers.infrastructure.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LikeJpaRepository extends JpaRepository<Like, LikeId> {

    // 삭제된 행 수를 돌려받으려고 JPQL 로 지운다. 변경 쿼리는 트랜잭션이 필요하며 Facade 의 트랜잭션에 참여한다.
    @Transactional
    @Modifying
    @Query("delete from Like l where l.id.userId = :userId and l.id.productId = :productId")
    int deleteByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") Long productId);
}
