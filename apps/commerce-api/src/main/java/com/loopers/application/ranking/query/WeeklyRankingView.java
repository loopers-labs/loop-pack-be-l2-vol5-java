package com.loopers.application.ranking.query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 주간 랭킹 조회 전용 DTO 묶음 (DR-31). 엔티티를 담지 않는다. */
public final class WeeklyRankingView {
    private WeeklyRankingView() {
    }

    /** 기간의 공개 pointer 가 가리키는 snapshot. frozenAtUtc 는 입력 고정 시각(UTC 값). */
    public record Publication(String snapshotId, long revision, LocalDateTime frozenAtUtc) {
    }

    /** 공개 조건(삭제되지 않은 상품)을 통과한 한 행. rank 는 저장된 계산 순위라 간격이 있을 수 있다. 표시 정보는 현재 상품·브랜드·좋아요 수. */
    public record Entry(int rank, long score, Long productId, String name, Long price, Long brandId, String brandName, long likeCount) {
    }

    /** publication 이 null 이면 아직 공개된 완성본이 없다 (items 는 빈 목록). */
    public record Weekly(LocalDate periodStart, LocalDate periodEnd, Publication publication, List<Entry> items) {
        public boolean published() {
            return publication != null;
        }
    }
}
