package com.loopers.product.infrastructure;

import com.loopers.product.domain.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface ProductJpaRepository extends JpaRepository<Product, Long> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findForOrder(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findForLike(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findForStock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findForWrite(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.brandId = :brandId order by p.id asc")
    List<Product> findAllForBrandDelete(@Param("brandId") Long brandId);

    List<Product> findAllByBrandId(Long brandId);

    List<Product> findAllByBrandIdAndName(Long brandId, String name);

    List<Product> findAllByBrandId(Long brandId, Pageable pageable);

    long countByBrandId(Long brandId);

    @Query("""
        select p from Product p
        where p.deletedAt is null
          and (:brandId is null or p.brandId = :brandId)
        order by p.createdAt desc, p.id asc
        """)
    List<Product> findCustomerProductsByLatest(
        @Param("brandId") Long brandId,
        Pageable pageable
    );

    @Query("""
        select p from Product p
        where p.deletedAt is null
          and (:brandId is null or p.brandId = :brandId)
        order by p.price asc, p.id asc
        """)
    List<Product> findCustomerProductsByPrice(
        @Param("brandId") Long brandId,
        Pageable pageable
    );

    interface RankedCandidate {
        Long getProductId();
        Long getEligibleProductId();
        Long getLikeCount();
    }

    @Query(value = """
        select ranked.product_id as productId, p.id as eligibleProductId, ranked.like_count as likeCount
        from (
            select product_id, count(*) as like_count
            from product_like
            group by product_id
            order by like_count desc, product_id asc
            limit :limit offset :offset
        ) ranked
        left join product p force index (idx_product_likes_filter)
          on p.id = ranked.product_id and p.deleted_at is null
        order by ranked.like_count desc, ranked.product_id asc
        """, nativeQuery = true)
    List<RankedCandidate> findRankedCandidates(
        @Param("offset") long offset,
        @Param("limit") int limit
    );

    @Query(value = """
        select page.id as productId, page.id as eligibleProductId, page.like_count as likeCount from (
            select candidate.id, count(*) as like_count
            from product candidate force index (uk_product_active_name)
            straight_join product_like l force index (idx_product_like_product_id)
              on l.product_id = candidate.id
            where candidate.brand_id = :brandId and candidate.deleted_at is null
            group by candidate.id
            order by like_count desc, candidate.id asc
            limit :limit offset :offset
        ) page
        order by page.like_count desc, page.id asc
        """, nativeQuery = true)
    List<RankedCandidate> findLikedCustomerProducts(
        @Param("brandId") Long brandId,
        @Param("offset") long offset,
        @Param("limit") int limit
    );

    @Query(value = """
        select count(*) from product p force index (uk_product_active_name)
        where p.brand_id = :brandId and p.deleted_at is null
          and exists (select 1 from product_like l where l.product_id = p.id)
        """, nativeQuery = true)
    long countLikedCustomerProducts(@Param("brandId") Long brandId);

    @Query(value = """
        select p.* from product p
        where p.deleted_at is null
          and (:brandId is null or p.brand_id = :brandId)
          and not exists (select 1 from product_like l where l.product_id = p.id)
        order by p.id asc
        limit :limit offset :offset
        """, nativeQuery = true)
    List<Product> findUnlikedCustomerProducts(
        @Param("brandId") Long brandId,
        @Param("offset") long offset,
        @Param("limit") int limit
    );
}
