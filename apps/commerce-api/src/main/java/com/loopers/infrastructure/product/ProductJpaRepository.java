package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductWithBrand;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<Product, Long> {

    String WITH_BRAND = "select new com.loopers.domain.product.ProductWithBrand("
        + "p.id, p.name, p.price, p.stock, b.id, b.name, p.createdAt, p.updatedAt) "
        + "from Product p join p.brand b ";

    Optional<Product> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.deletedAt is null")
    Optional<Product> findActiveForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids and p.deletedAt is null order by p.id")
    List<Product> findAllActiveForUpdate(@Param("ids") Collection<Long> ids);

    @Query("select p.id from Product p where p.id in :ids and p.deletedAt is null")
    List<Long> findActiveIds(@Param("ids") Collection<Long> ids);

    @Query(WITH_BRAND + "where p.id = :id and p.deletedAt is null")
    Optional<ProductWithBrand> findActiveWithBrand(@Param("id") Long id);

    @Query(
        value = WITH_BRAND + "where p.deletedAt is null and (:brandId is null or b.id = :brandId) "
            + "order by p.createdAt desc, p.id desc",
        countQuery = "select count(p) from Product p "
            + "where p.deletedAt is null and (:brandId is null or p.brand.id = :brandId)"
    )
    Page<ProductWithBrand> findActiveWithBrand(@Param("brandId") Long brandId, Pageable pageable);

    /**
     * 영속성 컨텍스트를 비우지 않는다. 같은 트랜잭션에서 이미 조회한 브랜드의 삭제가 commit 때 반영되어야 한다 (3주차 설계 2.3)
     */
    @Modifying
    @Query("update Product p set p.deletedAt = :deletedAt, p.updatedAt = :deletedAt "
        + "where p.brand.id = :brandId and p.deletedAt is null")
    int deleteAllOfBrand(@Param("brandId") Long brandId, @Param("deletedAt") ZonedDateTime deletedAt);
}
