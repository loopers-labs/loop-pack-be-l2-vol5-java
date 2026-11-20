package com.loopers.domain.ranking.publish;

import com.loopers.domain.ranking.RankedProduct;
import com.loopers.domain.ranking.score.ProductScore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 점수 내림차순, 동점이면 productId 오름차순. 같은 입력이면 항상 같은 순위가 나오도록 동점 규칙도 계산의 일부다. */
public final class RankingOrder {

    private static final Comparator<ProductScore> ORDER = Comparator
        .comparingLong(ProductScore::score).reversed()
        .thenComparing(ProductScore::productId);

    private RankingOrder() {}

    public static List<RankedProduct> rank(List<ProductScore> scores) {
        List<ProductScore> sorted = scores.stream().sorted(ORDER).toList();
        List<RankedProduct> ranked = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ProductScore score = sorted.get(i);
            ranked.add(new RankedProduct(score.productId(), score.score(), i + 1));
        }
        return ranked;
    }
}
