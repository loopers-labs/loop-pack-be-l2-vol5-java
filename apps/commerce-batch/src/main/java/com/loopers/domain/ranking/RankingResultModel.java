package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.math.BigInteger;

@Entity
@Table(
    name = "ranking_result",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_rr_snapshot_product", columnNames = {"snapshot_id", "product_id"}),
        @UniqueConstraint(name = "uk_rr_snapshot_rank", columnNames = {"snapshot_id", "calculated_rank"})
    }
)
@Getter
public class RankingResultModel extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false,
        columnDefinition = "varchar(64) character set ascii collate ascii_bin")
    private String snapshotId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "score", nullable = false, precision = 65, scale = 0)
    private BigInteger score;

    @Column(name = "calculated_rank", nullable = false)
    private Long calculatedRank;

    protected RankingResultModel() {}
}
