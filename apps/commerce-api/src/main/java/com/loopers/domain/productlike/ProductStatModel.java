package com.loopers.domain.productlike;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * AG-04 포함 개념 좋아요 수 (TB-08 product_stat, DR-36). 상품당 한 행이며 상품 생성 때 0 으로 만들어진다.
 * brand_id·deleted_at 은 TB-03 사본으로, likes_desc 정렬·브랜드 필터·삭제 상품 제외를 이 테이블만으로 하기 위해 둔다.
 * 만든 뒤에는 엔티티로 읽어 고치지 않고 조건 갱신으로만 바꾼다. 변경 감지로 저장하면 그 사이 반영된 좋아요 수를 옛 값으로 덮는다.
 */
@Entity
@Table(name = "product_stat",
    uniqueConstraints = @UniqueConstraint(name = "ux_product_stat_product", columnNames = "product_id"),
    indexes = {
        @Index(name = "ix_product_stat_likes", columnList = "deleted_at, like_count, product_id"),
        @Index(name = "ix_product_stat_brand_likes", columnList = "brand_id, deleted_at, like_count, product_id")
    })
public class ProductStatModel extends BaseEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "brand_id", nullable = false, updatable = false)
    private Long brandId;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    protected ProductStatModel() {}

    public ProductStatModel(Long productId, Long brandId) {
        this.productId = productId;
        this.brandId = brandId;
        this.likeCount = 0L;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getBrandId() {
        return brandId;
    }

    public long getLikeCount() {
        return likeCount;
    }
}
