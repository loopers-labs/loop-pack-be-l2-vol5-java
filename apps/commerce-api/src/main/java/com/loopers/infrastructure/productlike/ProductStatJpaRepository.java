package com.loopers.infrastructure.productlike;

import com.loopers.domain.productlike.ProductStatModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 갱신은 전부 조건 갱신이다 (DR-36). 영속성 컨텍스트를 비우지 않는다 — 같은 트랜잭션의 상품 변경이 버려지지 않게 한다.
 * 조건 갱신은 @PreUpdate 를 거치지 않아 updated_at 을 직접 넣는다.
 */
public interface ProductStatJpaRepository extends JpaRepository<ProductStatModel, Long> {
    Optional<ProductStatModel> findByProductId(Long productId);

    List<ProductStatModel> findAllByProductIdIn(Collection<Long> productIds);

    @Modifying
    @Query("update ProductStatModel s set s.likeCount = s.likeCount + 1, s.updatedAt = :now where s.productId = :productId")
    int increaseLikeCount(@Param("productId") Long productId, @Param("now") ZonedDateTime now);

    @Modifying
    @Query("update ProductStatModel s set s.likeCount = s.likeCount - 1, s.updatedAt = :now where s.productId = :productId")
    int decreaseLikeCount(@Param("productId") Long productId, @Param("now") ZonedDateTime now);

    @Modifying
    @Query("update ProductStatModel s set s.deletedAt = :now, s.updatedAt = :now where s.productId = :productId and s.deletedAt is null")
    int markDeleted(@Param("productId") Long productId, @Param("now") ZonedDateTime now);

    @Modifying
    @Query("update ProductStatModel s set s.deletedAt = :now, s.updatedAt = :now where s.brandId = :brandId and s.deletedAt is null")
    int markDeletedByBrandId(@Param("brandId") Long brandId, @Param("now") ZonedDateTime now);
}
