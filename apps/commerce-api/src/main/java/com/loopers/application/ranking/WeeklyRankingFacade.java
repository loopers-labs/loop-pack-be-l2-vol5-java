package com.loopers.application.ranking;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.ranking.PublishedSnapshot;
import com.loopers.domain.ranking.RankedScore;
import com.loopers.domain.ranking.WeeklyPeriod;
import com.loopers.domain.ranking.WeeklyRankingReader;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공개된 주간 랭킹 조회 (설계 D14). 읽은 점수 행 전체에 R4(행 수 == expected, ranking 전부 NOT NULL, 1..N 연속)를 다시 검증한 뒤(2차 방어),
 * 삭제 상품은 기존 공개 조건(ProductService.findActiveProducts)으로 제외하고 limit 을 적용한다. ranking 은 DB 값 그대로(재계산 금지, 간격 허용).
 * R4 위반은 NOT_PUBLISHED 나 빈 목록으로 숨기지 않고 500 으로 드러낸다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class WeeklyRankingFacade {

    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 100;

    private final WeeklyRankingReader weeklyRankingReader;
    private final ProductService productService;

    @Transactional(readOnly = true)
    public WeeklyRankingInfo getWeeklyRanking(String periodStart, int limit) {
        WeeklyPeriod period = WeeklyPeriod.parse(periodStart);
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new CoreException(ErrorType.BAD_REQUEST, "limit 은 1 이상 100 이하여야 합니다. limit=" + limit);
        }

        Optional<PublishedSnapshot> published = weeklyRankingReader.findPublished(period.periodStart());
        if (published.isEmpty()) {
            return new WeeklyRankingInfo(WeeklyRankingInfo.Status.NOT_PUBLISHED,
                period.periodStart(), period.periodEnd(), null, null, List.of());
        }

        PublishedSnapshot snapshot = published.get();
        List<RankedScore> scores = weeklyRankingReader.findRankedScores(snapshot.snapshotId());
        verifyPublishedShape(snapshot, scores);
        Map<Long, Product> activeProducts = productService
            .findActiveProducts(scores.stream().map(RankedScore::productId).toList())
            .stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        List<WeeklyRankingInfo.Item> items = scores.stream()
            .filter(score -> activeProducts.containsKey(score.productId()))
            .limit(limit)
            .map(score -> new WeeklyRankingInfo.Item(
                score.productId(), activeProducts.get(score.productId()).getName(), score.score(), score.ranking()))
            .toList();

        return new WeeklyRankingInfo(WeeklyRankingInfo.Status.PUBLISHED,
            period.periodStart(), period.periodEnd(), snapshot.snapshotId(), snapshot.frozenAt(), items);
    }

    /**
     * R4 읽기 시점 검증 (삭제 상품 제외 전, 전체 행 기준). (1) 행 수 == expected_product_count (2) ranking 전부 NOT NULL
     * (3) ranking 오름차순에서 1..N 연속. 0행이면 (1)만 적용된다(0 == 0).
     */
    private void verifyPublishedShape(PublishedSnapshot snapshot, List<RankedScore> scores) {
        if (scores.size() != snapshot.expectedProductCount()) {
            throw r4Violation(snapshot, "row count " + scores.size() + " != expected_product_count " + snapshot.expectedProductCount());
        }
        for (int i = 0; i < scores.size(); i++) {
            Integer ranking = scores.get(i).ranking();
            if (ranking == null) {
                throw r4Violation(snapshot, "ranking is NULL for productId " + scores.get(i).productId());
            }
            if (ranking != i + 1) {
                throw r4Violation(snapshot, "ranking not contiguous: expected " + (i + 1) + " but was " + ranking
                    + " for productId " + scores.get(i).productId());
            }
        }
    }

    private CoreException r4Violation(PublishedSnapshot snapshot, String detail) {
        log.error("weekly ranking snapshot {} violates R4: {}", snapshot.snapshotId(), detail);
        return new CoreException(ErrorType.INTERNAL_ERROR,
            "weekly ranking snapshot " + snapshot.snapshotId() + " violates R4: " + detail);
    }
}
