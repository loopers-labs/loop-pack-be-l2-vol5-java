package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "ranking_publication",
    uniqueConstraints = @UniqueConstraint(name = "uk_rp_period", columnNames = {"period_start"})
)
@Getter
public class RankingPublicationModel extends BaseEntity {

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "published_snapshot_id", nullable = false,
        columnDefinition = "varchar(64) character set ascii collate ascii_bin")
    private String publishedSnapshotId;

    @Column(name = "published_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime publishedAt;

    protected RankingPublicationModel() {}
}
