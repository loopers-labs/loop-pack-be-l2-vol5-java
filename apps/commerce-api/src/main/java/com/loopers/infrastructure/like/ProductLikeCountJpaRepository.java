package com.loopers.infrastructure.like;

import com.loopers.domain.like.ProductLikeCount;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 상품별 좋아요 수 집계. 변경 쿼리는 트랜잭션이 필요하며 Facade 의 트랜잭션에 참여한다.
 * 행은 상품 생성 때 0으로 넣고, 증감은 {@code UPDATE} 만 쓴다(upsert 하지 않는다). 삭제된 상품은 행이 없으므로,
 * 삭제와 좋아요가 경쟁해도 삭제된 상품의 행이 되살아나지 않는다.
 */
public interface ProductLikeCountJpaRepository extends JpaRepository<ProductLikeCount, Long> {

    @Transactional
    @Modifying
    @Query("update ProductLikeCount c set c.likeCount = c.likeCount + 1 where c.productId = :productId")
    int increase(@Param("productId") Long productId);

    // 0 아래로 내려가지 않게 막는다. 행이 없거나 0이면 영향 행 수는 0이다.
    @Transactional
    @Modifying
    @Query("update ProductLikeCount c set c.likeCount = c.likeCount - 1 where c.productId = :productId and c.likeCount > 0")
    int decrease(@Param("productId") Long productId);

    @Query("select c.likeCount from ProductLikeCount c where c.productId = :productId")
    Optional<Long> findLikeCount(@Param("productId") Long productId);

    // 행이 없는 상품은 결과에 없다(행이 있으면 0도 담긴다).
    @Query(
        "select new com.loopers.infrastructure.like.ProductLikeCountRow(c.productId, c.likeCount) "
            + "from ProductLikeCount c where c.productId in :productIds"
    )
    List<ProductLikeCountRow> findLikeCounts(@Param("productIds") Collection<Long> productIds);

    // 노출 상품만 행이 있으므로 삭제 상품을 거르지 않고 인덱스 (like_count DESC, product_id DESC) 순서로 페이지만 읽는다.
    // 쿼리에는 없지만 Spring Data 가 Pageable 의 offset·size 를 SQL 의 limit 으로 붙인다(반환 타입이 List 여도 같다).
    @Query("select c.productId from ProductLikeCount c order by c.likeCount desc, c.productId desc")
    List<Long> findProductIdsOrderByLikeCountDesc(Pageable pageable);

    // 새 상품은 좋아요가 없으므로 0으로 만든다. 엔티티에 생성자를 두지 않으려고 쿼리로 넣는다.
    @Transactional
    @Modifying
    @Query(value = "insert into product_like_counts (product_id, like_count) values (:productId, 0)", nativeQuery = true)
    void insertZero(@Param("productId") Long productId);

    @Transactional
    @Modifying
    @Query("delete from ProductLikeCount c where c.productId in :productIds")
    int deleteAllByProductIdIn(@Param("productIds") Collection<Long> productIds);
}
