package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductWithBrand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<Product, Long> {

    String WITH_BRAND = "select new com.loopers.domain.product.ProductWithBrand("
        + "p.id, p.name, p.price, p.stock, b.id, b.name, p.createdAt, p.updatedAt) "
        + "from Product p join p.brand b ";

    Optional<Product> findByIdAndDeletedAtIsNull(Long id);

    @Query(WITH_BRAND + "where p.id = :id and p.deletedAt is null")
    Optional<ProductWithBrand> findActiveWithBrand(@Param("id") Long id);

    @Query(
        value = WITH_BRAND + "where p.deletedAt is null and (:brandId is null or b.id = :brandId) "
            + "order by p.createdAt desc, p.id desc",
        countQuery = "select count(p) from Product p "
            + "where p.deletedAt is null and (:brandId is null or p.brand.id = :brandId)"
    )
    Page<ProductWithBrand> findActiveWithBrand(@Param("brandId") Long brandId, Pageable pageable);
}
