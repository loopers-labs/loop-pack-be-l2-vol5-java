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
    @Query("update ProductJpaEntity p set p.stock = p.stock + :quantity, p.updatedAt = :now "
            + "where p.id = :id and p.stock <= :maximumBeforeReturn")
    int restoreReservedStock(
            @Param("id") long id,
            @Param("quantity") int quantity,
            @Param("maximumBeforeReturn") int maximumBeforeReturn,
            @Param("now") ZonedDateTime now);

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

    // 상품 목록·상세·좋아요 목록 조회
    String PRODUCT_FIELDS = """
            select p.id as productId, p.name as name, p.price as price,
                   b.id as brandId, b.name as brandName,
                   coalesce(c.like_count, 0) as likeCount
            """;

    // 고객에게 노출할 상품의 조건을 목록, 상세, 좋아요 목록과 COUNT에 동일하게 적용한다.
    String VISIBLE_PRODUCTS = """
            from products p
            join brands b on b.id = p.brand_id
            where p.deleted_at is null and b.deleted_at is null
            """;

    String BRAND_FILTER = " and p.brand_id = :brandId ";
    // JPA의 int OFFSET 제한 없이 기존 API의 long OFFSET 범위를 유지한다.
    String PAGE_LIMIT = " limit :size offset :offset ";

    // 카운터가 없는 새 상품은 0개다. 상품별 관계 COUNT 없이 저장된 집계를 읽는다.
    String COUNTED_PRODUCTS = """
            from products p
            join brands b on b.id = p.brand_id
            left join product_like_counts c on c.product_id = p.id
            where p.deleted_at is null and b.deleted_at is null
            """;

    // 좋아요 수와 무관한 정렬은 먼저 페이지를 선택해 카운터 JOIN도 해당 상품에만 수행한다.
    String LIST_CANDIDATES = """
            with list_products as (
                select p.id, p.name, p.price, p.created_at,
                       b.id as brand_id, b.name as brand_name
            """ + VISIBLE_PRODUCTS;
    String LIST_COUNTS = """
            )
            select p.id as productId, p.name as name, p.price as price,
                   p.brand_id as brandId, p.brand_name as brandName,
                   coalesce(c.like_count, 0) as likeCount
            from list_products p
            left join product_like_counts c on c.product_id=p.id
            """;

    @Query(value = LIST_CANDIDATES
            + " order by p.created_at desc, p.id desc " + PAGE_LIMIT + LIST_COUNTS
            + " order by p.created_at desc, p.id desc ", nativeQuery = true)
    List<ProductRow> findLatest(@Param("size") int size, @Param("offset") long offset);

    @Query(value = LIST_CANDIDATES
            + " order by p.price asc, p.id desc " + PAGE_LIMIT + LIST_COUNTS
            + " order by p.price asc, p.id desc ", nativeQuery = true)
    List<ProductRow> findLowestPrice(@Param("size") int size, @Param("offset") long offset);

    @Query(value = PRODUCT_FIELDS + COUNTED_PRODUCTS
            + " order by likeCount desc, p.id desc " + PAGE_LIMIT, nativeQuery = true)
    List<ProductRow> findMostLiked(@Param("size") int size, @Param("offset") long offset);

    @Query(value = LIST_CANDIDATES + BRAND_FILTER
            + " order by p.created_at desc, p.id desc " + PAGE_LIMIT + LIST_COUNTS
            + " order by p.created_at desc, p.id desc ", nativeQuery = true)
    List<ProductRow> findLatestByBrandId(
            @Param("brandId") long brandId, @Param("size") int size, @Param("offset") long offset);

    @Query(value = LIST_CANDIDATES + BRAND_FILTER
            + " order by p.price asc, p.id desc " + PAGE_LIMIT + LIST_COUNTS
            + " order by p.price asc, p.id desc ", nativeQuery = true)
    List<ProductRow> findLowestPriceByBrandId(
            @Param("brandId") long brandId, @Param("size") int size, @Param("offset") long offset);

    @Query(value = PRODUCT_FIELDS + COUNTED_PRODUCTS + BRAND_FILTER
            + " order by likeCount desc, p.id desc " + PAGE_LIMIT, nativeQuery = true)
    List<ProductRow> findMostLikedByBrandId(
            @Param("brandId") long brandId, @Param("size") int size, @Param("offset") long offset);

    @Query(value = "select p.id " + VISIBLE_PRODUCTS + " and p.id in (:ids)", nativeQuery = true)
    List<Long> findVisibleIds(@Param("ids") List<Long> ids);

    @Query(value = "select count(*) " + VISIBLE_PRODUCTS, nativeQuery = true)
    long countVisible();

    @Query(value = "select count(*) " + VISIBLE_PRODUCTS + BRAND_FILTER, nativeQuery = true)
    long countVisibleByBrandId(@Param("brandId") long brandId);

    @Query(value = PRODUCT_FIELDS + COUNTED_PRODUCTS + " and p.id = :productId", nativeQuery = true)
    Optional<ProductRow> findVisibleById(@Param("productId") long productId);

    @Query(value = PRODUCT_FIELDS + COUNTED_PRODUCTS + """
             and exists (
                 select 1 from product_likes own
                 where own.product_id = p.id and own.user_id = :userId
             )
             order by p.id desc
            """, nativeQuery = true)
    List<ProductRow> findLikedByUserId(@Param("userId") long userId);

    interface ProductRow {
        long getProductId();
        String getName();
        long getPrice();
        long getBrandId();
        String getBrandName();
        long getLikeCount();
    }
}
