package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByIdAndDeletedAtIsNull(Long id);

    List<Product> findAllByIdInAndDeletedAtIsNull(Collection<Long> ids);

    Page<Product> findAllByDeletedAtIsNullOrderByIdDesc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.deletedAt is null")
    Optional<Product> findActiveForUpdate(@Param("id") Long id);

    // 여러 상품을 id 오름차순으로 한 번에 잠근다. 품목 순서대로 하나씩 잠그면 요청마다 순서가 달라 교착이 생긴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids and p.deletedAt is null order by p.id")
    List<Product> findAllActiveByIdsForUpdate(@Param("ids") Collection<Long> ids);

    // 삭제된 상품도 포함한다(결제 실패로 확보했던 재고를 돌려줄 때). 순서는 위와 같이 id 오름차순이라 활성 상품만 잠그는
    // 차감 경로와 같은 순서로 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> findAllByIdsForUpdate(@Param("ids") Collection<Long> ids);

    // 잠금 순서를 id 오름차순으로 고정해 같은 상품들을 잠그는 요청끼리 서로 엇갈리지 않게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.brandId = :brandId and p.deletedAt is null order by p.id")
    List<Product> findAllActiveByBrandIdForUpdate(@Param("brandId") Long brandId);

    // 고객 상품 목록 쿼리 3종(latest, price_asc, likes_desc).
    // - 정렬은 쿼리에 고정한다. 호출하는 쪽이 Pageable 에 Sort 를 실어도 반영하지 않으며, 동률은 모두 상품 id 내림차순이다.
    // - 삭제된 상품은 제외한다 (p.deletedAt is null).
    // - `:brandId is null or ...` 의 brandId 는 상품의 컬럼이 아니라 목록 API 의 선택 필터 인자다.
    //   null 이면 브랜드를 가리지 않고 전체를, 값이 있으면 그 브랜드의 상품만 조회한다. (상품의 brand_id 는 null 일 수 없다)
    @Query(
        value = "select p from Product p where p.deletedAt is null and (:brandId is null or p.brandId = :brandId) "
            + "order by p.id desc",
        countQuery = "select count(p) from Product p where p.deletedAt is null and (:brandId is null or p.brandId = :brandId)"
    )
    Page<Product> findAllActiveOrderByLatest(@Param("brandId") Long brandId, Pageable pageable);

    @Query(
        value = "select p from Product p where p.deletedAt is null and (:brandId is null or p.brandId = :brandId) "
            + "order by p.price.amount asc, p.id desc",
        countQuery = "select count(p) from Product p where p.deletedAt is null and (:brandId is null or p.brandId = :brandId)"
    )
    Page<Product> findAllActiveOrderByPriceAsc(@Param("brandId") Long brandId, Pageable pageable);

    // likes_desc (브랜드 지정): 상품과 좋아요 집계를 id 로 left join 해 집계 내림차순으로 정렬한다. 브랜드 인덱스가 먼저 범위를
    // 좁히므로 조인 순서를 정할 필요가 없다. 총 개수 쿼리는 join 없이 상품만 센다.
    // 브랜드를 지정하지 않는 likes_desc 는 집계 테이블에서 페이지의 상품 id 를 먼저 읽는다(ProductLikeCountJpaRepository).
    @Query(
        value = "select p from Product p left join ProductLikeCount c on c.productId = p.id "
            + "where p.deletedAt is null and p.brandId = :brandId order by coalesce(c.likeCount, 0) desc, p.id desc",
        countQuery = "select count(p) from Product p where p.deletedAt is null and p.brandId = :brandId"
    )
    Page<Product> findAllActiveOrderByLikesDescOfBrand(@Param("brandId") Long brandId, Pageable pageable);

    @Query("select count(p) from Product p where p.deletedAt is null")
    long countActive();

    // 좋아요와 상품을 id 로 join 해 삭제된 상품을 제외한 채 한 번에 페이징한다. 연관관계 없이 명시적 on 절을 쓴다.
    @Query(
        value = "select p from Product p join Like l on l.id.productId = p.id "
            + "where l.id.userId = :userId and p.deletedAt is null "
            + "order by l.likedAt desc, p.id desc",
        countQuery = "select count(p) from Product p join Like l on l.id.productId = p.id "
            + "where l.id.userId = :userId and p.deletedAt is null"
    )
    Page<Product> findAllActiveLikedBy(@Param("userId") Long userId, Pageable pageable);
}
