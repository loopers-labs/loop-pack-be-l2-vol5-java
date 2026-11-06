package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductListRevisionId;
import com.loopers.domain.product.ProductListRevisionModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductListRevisionJpaRepository extends JpaRepository<ProductListRevisionModel, ProductListRevisionId> {

    @Modifying
    @Query(
        value = "INSERT INTO product_list_revision (brand_id, slot, latest_revision, price_revision, likes_revision) "
            + "VALUES (:brandId, :slot, :latest, :price, :likes) "
            + "ON DUPLICATE KEY UPDATE latest_revision = latest_revision + :latest, "
            + "price_revision = price_revision + :price, likes_revision = likes_revision + :likes",
        nativeQuery = true
    )
    void increase(
        @Param("brandId") Long brandId,
        @Param("slot") long slot,
        @Param("latest") int latest,
        @Param("price") int price,
        @Param("likes") int likes
    );

    @Query("select sum(r.latestRevision) as latest, sum(r.priceRevision) as price, sum(r.likesRevision) as likes "
        + "from ProductListRevisionModel r where r.id.brandId = :brandId")
    ProductListRevisionSums sumByBrandId(@Param("brandId") Long brandId);

    @Query("select sum(r.latestRevision) as latest, sum(r.priceRevision) as price, sum(r.likesRevision) as likes "
        + "from ProductListRevisionModel r")
    ProductListRevisionSums sumAll();
}
