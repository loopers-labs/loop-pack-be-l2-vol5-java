package com.loopers.infrastructure.product;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {
    interface ProductId {
        Long getId();
    }

    List<ProductId> findIdsByBrandIdAndDeletedAtIsNullOrderByIdAsc(long brandId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update ProductJpaEntity p set p.deletedAt = :now, p.updatedAt = :now where p.id = :id"
                + " and p.deletedAt is null")
    int markDeleted(@Param("id") long id, @Param("now") ZonedDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update ProductJpaEntity p set p.stock = p.stock - :quantity, p.updatedAt = :now "
                    + "where p.id = :id and p.deletedAt is null and p.stock >= :quantity")
    int deductStock(
            @Param("id") long id, @Param("quantity") int quantity, @Param("now") ZonedDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update ProductJpaEntity p set p.stock = :stock, p.updatedAt = :now where p.id = :id"
                + " and p.deletedAt is null")
    int setStock(@Param("id") long id, @Param("stock") int stock, @Param("now") ZonedDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update ProductJpaEntity p set p.name = :name, p.price = :price, p.updatedAt = :now "
                    + "where p.id = :id and p.deletedAt is null")
    int updateInformation(
            @Param("id") long id,
            @Param("name") String name,
            @Param("price") long price,
            @Param("now") ZonedDateTime now);

    // 갱신 실패의 이유를 구분할 때만 사용한다. RR의 과거 스냅샷으로 삭제 여부를 판단하지 않는다.
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<ProductJpaEntity> findCurrentById(long id);
}
