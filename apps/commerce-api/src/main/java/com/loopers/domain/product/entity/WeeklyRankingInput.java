package com.loopers.domain.product.entity;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * snapshot 에 고정한 구매 활동의 복사본. 원본은 늦은 구매로 계속 바뀌어 같은 행 집합을 다시 고를 수 없다 (9주차 D-186).
 * api 는 읽지 않지만 스키마를 한곳에서 정의하려고 둔다 (D-183)
 */
@Getter
@Entity
@Table(
    name = "weekly_ranking_input",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_weekly_ranking_input_snapshot_order_product",
        columnNames = {"snapshot_id", "order_id", "product_id"}
    ),
    // reader 가 snapshot 의 상품 id 를 정렬해 읽고, processor 가 상품별 합을 구함
    indexes = @Index(name = "idx_weekly_ranking_input_snapshot_product", columnList = "snapshot_id, product_id")
)
public class WeeklyRankingInput extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false)
    private ZonedDateTime confirmedAt;

    protected WeeklyRankingInput() {}
}
