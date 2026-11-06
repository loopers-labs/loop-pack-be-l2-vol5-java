package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductRevisionModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRevisionJpaRepository extends JpaRepository<ProductRevisionModel, Long> {

    @Modifying
    @Query(
        value = "INSERT INTO product_revision (product_id, revision) VALUES (:productId, 1) "
            + "ON DUPLICATE KEY UPDATE revision = revision + 1",
        nativeQuery = true
    )
    void increase(@Param("productId") Long productId);
}
