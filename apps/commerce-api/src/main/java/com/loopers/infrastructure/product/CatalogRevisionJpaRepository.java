package com.loopers.infrastructure.product;

import com.loopers.domain.product.CatalogRevisionModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;

/**
 * 갱신은 조건 갱신뿐이다 (DR-37). 영속성 컨텍스트를 비우지 않는다 — 같은 트랜잭션의 상품·브랜드 변경이 버려지지 않게 한다.
 * 조건 갱신은 @PreUpdate 를 거치지 않아 updated_at 을 직접 넣는다.
 */
public interface CatalogRevisionJpaRepository extends JpaRepository<CatalogRevisionModel, Long> {
    @Modifying
    @Query("update CatalogRevisionModel r set r.revision = r.revision + 1, r.updatedAt = :now where r.name = :name")
    int increaseRevision(@Param("name") String name, @Param("now") ZonedDateTime now);
}
