package com.loopers.application.ranking;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.domain.ranking.PublishedWeeklySnapshot;
import com.loopers.domain.ranking.WeeklyRankingEntry;
import com.loopers.domain.ranking.WeeklyRankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공개된 주간 랭킹에 상품 표시 정보를 붙인다. 표시 정보는 기존 상품 조회 규칙(ProductFacade.getProductsByIds)을 그대로 쓰고,
 * 순위를 읽은 뒤 그 사이 삭제된 상품은 표시하지 않는다.
 */
@RequiredArgsConstructor
@Component
public class RankingFacade {

    private final WeeklyRankingService weeklyRankingService;
    private final ProductFacade productFacade;

    public WeeklyRankingInfo getWeeklyRanking(LocalDate periodStart, int limit) {
        Optional<PublishedWeeklySnapshot> published = weeklyRankingService.getPublished(periodStart, limit);
        if (published.isEmpty()) {
            return WeeklyRankingInfo.notPublished(periodStart);
        }
        PublishedWeeklySnapshot snapshot = published.get();
        List<WeeklyRankingEntry> entries = weeklyRankingService.getVisibleEntries(snapshot, limit);

        Map<Long, ProductInfo> products = entries.isEmpty()
            ? Map.of()
            : productFacade.getProductsByIds(entries.stream().map(WeeklyRankingEntry::productId).toList()).stream()
                .collect(Collectors.toMap(ProductInfo::id, Function.identity()));
        List<WeeklyRankingInfo.Item> items = entries.stream()
            .filter(entry -> products.containsKey(entry.productId()))
            .map(entry -> {
                ProductInfo product = products.get(entry.productId());
                return new WeeklyRankingInfo.Item(
                    entry.rank(), entry.productId(), entry.score(), product.name(), product.brandName(), product.price());
            })
            .toList();

        return new WeeklyRankingInfo(
            WeeklyRankingInfo.Status.PUBLISHED, snapshot.periodStart(), snapshot.periodEnd(),
            snapshot.snapshotId(), snapshot.revision(), snapshot.frozenAt(), items);
    }
}
