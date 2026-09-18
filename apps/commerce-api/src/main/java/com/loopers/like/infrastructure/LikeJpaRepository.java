package com.loopers.like.infrastructure;

import com.loopers.like.domain.Like;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface LikeJpaRepository extends JpaRepository<Like, Long> {

    Optional<Like> findByUserIdAndProductId(Long userId, Long productId);

    long countByProductId(Long productId);

    @Query("""
        select l from Like l, Product p
        where l.productId = p.id
          and l.userId = :userId
          and p.deletedAt is null
        order by l.createdAt desc, l.id asc
        """)
    List<Like> findActiveProductLikesByUserId(
        @Param("userId") Long userId,
        Pageable pageable
    );

    @Query("""
        select count(l) from Like l, Product p
        where l.productId = p.id
          and l.userId = :userId
          and p.deletedAt is null
        """)
    long countActiveProductLikesByUserId(@Param("userId") Long userId);
}
