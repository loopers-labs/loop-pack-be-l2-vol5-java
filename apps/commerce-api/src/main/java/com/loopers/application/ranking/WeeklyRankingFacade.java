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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공개된 주간 랭킹 조회 (설계 D14). 삭제 상품은 기존 공개 조건(ProductService.findActiveProducts)으로 제외한 뒤 limit 을 적용하고,
 * ranking 은 DB 값 그대로 둔다(재계산 금지, 간격 허용).
 */
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
}
