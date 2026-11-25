package com.loopers.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

/**
 * 고정 입력 — 입력 고정 step이 snapshot에 복사해 둔 구매 활동 행 (W9 D-2·D-3). 이후 바뀌지 않는다.
 * 테이블 정의용 매핑이다. batch는 JDBC로 쓰고 읽는다.
 * UNIQUE (snapshot_id, product_id, order_id) 순서 덕분에 점수 계산 step의 상품별 GROUP BY가 정렬 없이 흐른다 (D-4).
 */
@Entity
@Table(
    name = "ranking_snapshot_input",
    uniqueConstraints = @UniqueConstraint(name = "uk_snapshot_input_product_order", columnNames = {"snapshot_id", "product_id", "order_id"})
)
public class RankingSnapshotInput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false, length = 32)
    private String snapshotId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false)
    private ZonedDateTime confirmedAt;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    protected RankingSnapshotInput() {}
}
