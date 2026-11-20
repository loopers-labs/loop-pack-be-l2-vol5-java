package com.loopers.application.ranking;

import com.loopers.application.ranking.dto.WeeklyRankingInfo;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.ranking.RankedProduct;
import com.loopers.domain.ranking.RankingWeek;
import com.loopers.domain.ranking.WeeklyRankingService;
import com.loopers.domain.ranking.WeeklyRankingSnapshot;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class WeeklyRankingFacade {

    public static final int MAX_LIMIT = 100;
    // 요청 파라미터 기본값으로 쓰려고 컴파일 타임 상수(문자열)로 둔다.
    public static final String DEFAULT_LIMIT = "10";

    private final WeeklyRankingService weeklyRankingService;
    private final BrandService brandService;

    /**
     * 공개 pointer 가 가리키는 완성본만 읽는다. pointer·snapshot·ranking·상품을 한 readOnly TX(같은 읽기 시점)에서 읽어
     * 공개가 바뀌는 중에도 서로 다른 snapshot 의 행이 섞이지 않는다.
     */
    @Transactional(readOnly = true)
    public WeeklyRankingInfo getWeekly(String periodStart, int limit) {
        RankingWeek week = RankingWeek.from(periodStart);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new CoreException(ErrorType.INVALID_INPUT, "limit 은 1 이상 " + MAX_LIMIT + " 이하여야 합니다.");
        }

        Optional<WeeklyRankingSnapshot> published = weeklyRankingService.findPublished(week);
        if (published.isEmpty()) {
            return WeeklyRankingInfo.notPublished(week);
        }
        List<RankedProduct> ranked = weeklyRankingService.getVisible(published.get(), limit);
        Map<Long, Brand> brands = brandService.getAllByIds(
            ranked.stream().map(item -> item.product().getBrandId()).distinct().toList());
        List<WeeklyRankingInfo.Item> items = ranked.stream()
            .map(item -> WeeklyRankingInfo.Item.of(item, brandOf(item, brands)))
            .toList();
        return WeeklyRankingInfo.published(week, published.get(), items);
    }

    // 상품이 있는 브랜드는 삭제할 수 없어 항상 있어야 한다. 없으면 데이터가 깨진 것으로 보고 드러낸다.
    private Brand brandOf(RankedProduct item, Map<Long, Brand> brands) {
        Brand brand = brands.get(item.product().getBrandId());
        if (brand == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR,
                "상품 " + item.product().getId() + " 의 브랜드 " + item.product().getBrandId() + " 가 없습니다.");
        }
        return brand;
    }
}
