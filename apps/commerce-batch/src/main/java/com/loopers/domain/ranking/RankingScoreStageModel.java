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
    name = "ranking_score_stage",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_rss_snapshot_product", columnNames = {"snapshot_id", "product_id"})
)
@Getter
public class RankingScoreStageModel extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false,
        columnDefinition = "varchar(64) character set ascii collate ascii_bin")
    private String snapshotId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "score", nullable = false, precision = 65, scale = 0)
    private BigInteger score;

    protected RankingScoreStageModel() {}
}
