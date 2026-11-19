package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/** snapshot 에 고정한 구매 활동 한 행. 고정 뒤에는 바꾸거나 추가하지 않는다. */
@Getter
@Entity
@Table(
    name = "weekly_ranking_snapshot_input",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_weekly_ranking_snapshot_input", columnNames = {"snapshot_id", "order_id", "product_id"}
    ),
    // 상품별 합계를 테이블 행을 읽지 않고 인덱스만으로 계산한다.
    indexes = @Index(name = "idx_weekly_ranking_snapshot_input_product", columnList = "snapshot_id, product_id, quantity")
)
public class WeeklyRankingSnapshotInput extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false)
    private ZonedDateTime confirmedAt;

    // JPA 가 사용하는 기본 생성자. 행은 원본에서 INSERT … SELECT 로만 만든다.
    protected WeeklyRankingSnapshotInput() {}

    public SnapshotInputLine toLine() {
        return new SnapshotInputLine(orderId, productId, quantity, confirmedAt);
    }
}
