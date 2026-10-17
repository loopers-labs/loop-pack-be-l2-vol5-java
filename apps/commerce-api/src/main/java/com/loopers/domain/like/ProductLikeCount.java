package com.loopers.domain.like;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 상품별 좋아요 수를 모아 둔 집계. 좋아요 수의 <b>단일 출처</b>이며 좋아요 관계({@link Like})가 바뀔 때 함께 바뀐다.
 *
 * <p>노출 중인 상품만 행을 가진다. 상품을 저장(생성)할 때 만들어지고 삭제 처리할 때 지워지므로, 브랜드 없는 좋아요순 목록은
 * 이 테이블의 인덱스 {@code (like_count DESC, product_id DESC)} 순서로 페이지의 상품 id 만 읽으면 삭제 상품을 따로
 * 거르지 않고도 20행에서 멈출 수 있다. 좋아요 TX 는 이 행만 바꾸고 {@code products} 행은 건드리지 않아 주문·브랜드 삭제
 * 같은 상품 쓰기와 경쟁하지 않는다.
 *
 * <p>행은 영속성 컨텍스트로 읽고 쓰지 않고 저장소의 변경·조회 쿼리로만 다루므로 getter 가 없다. 증감 규칙과 정합성은
 * {@code LikeRepositoryImpl}·{@code ProductRepositoryImpl} 이 지킨다.
 */
@Entity
@Table(
    name = "product_like_counts",
    indexes = @Index(name = "idx_product_like_counts_ranking", columnList = "like_count DESC, product_id DESC")
)
public class ProductLikeCount {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    // JPA 가 사용하는 기본 생성자. 행은 저장소의 쿼리로 만든다.
    protected ProductLikeCount() {}
}
