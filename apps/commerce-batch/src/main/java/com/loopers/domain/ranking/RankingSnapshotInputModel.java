package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "ranking_snapshot_input",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_rsi_snapshot_order_product", columnNames = {"snapshot_id", "order_id", "product_id"}),
    indexes = @Index(name = "idx_rsi_snapshot_product", columnList = "snapshot_id, product_id")
)
@Getter
public class RankingSnapshotInputModel extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false,
        columnDefinition = "varchar(64) character set ascii collate ascii_bin")
    private String snapshotId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "confirmed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime confirmedAt;

    protected RankingSnapshotInputModel() {}
}
