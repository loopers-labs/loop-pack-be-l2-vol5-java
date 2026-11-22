package com.loopers.application.ranking;

import com.loopers.application.product.CustomerProductInfo;
import com.loopers.application.product.ProductQueryService;
import com.loopers.domain.ranking.WeeklyRankingReader;
import com.loopers.domain.ranking.WeeklyRankingReader.Entry;
import com.loopers.domain.ranking.WeeklyRankingReader.PublishedSnapshot;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공개 pointer가 가리키는 완성본만 읽는다. 계산 중인 stage나 다른 주의 결과를 대신 보여 주지 않는다.
 * 현재 고객 공개 조건(상품·브랜드 삭제 여부)을 적용한 뒤 limit을 채운다.
 */
@Component
@RequiredArgsConstructor
public class WeeklyRankingQueryService {
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 100;

    private final WeeklyRankingReader rankings;
    private final ProductQueryService products;

    /** 한 읽기 TX 안에서 pointer와 완성 랭킹을 읽어, 읽는 도중 pointer가 바뀌어도 서로 다른 snapshot을 섞지 않는다. */
    @Transactional(readOnly = true)
    public WeeklyRankingInfo get(String periodStartText, int limit) {
        LocalDate periodStart = parseMonday(periodStartText);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new CoreException(ErrorType.BAD_REQUEST, "limit은 1 이상 " + MAX_LIMIT + " 이하여야 합니다.");
        }

        Optional<PublishedSnapshot> published = rankings.findPublished(periodStart);
        if (published.isEmpty()) {
            return WeeklyRankingInfo.notPublished(periodStart);
        }
        PublishedSnapshot snapshot = published.get();
        return new WeeklyRankingInfo(periodStart, periodStart.plusWeeks(1), WeeklyRankingInfo.Status.PUBLISHED,
            snapshot.snapshotId(), snapshot.revision(), snapshot.inputFrozenAt(),
            visibleItems(snapshot.snapshotId(), limit));
    }

    /** 순위 순서로 limit개씩 읽으며 지금 고객에게 보이는 상품만 모은다. 계산 순위는 그대로 둔다. */
    private List<WeeklyRankingInfo.Item> visibleItems(String snapshotId, int limit) {
        List<WeeklyRankingInfo.Item> items = new ArrayList<>();
        int offset = 0;
        while (items.size() < limit) {
            List<Entry> page = rankings.findEntries(snapshotId, offset, limit);
            if (page.isEmpty()) {
                break;
            }
            Map<Long, CustomerProductInfo> visible = products.getActiveByIdsInOrder(
                    page.stream().map(Entry::productId).toList()).stream()
                .collect(Collectors.toMap(CustomerProductInfo::id, Function.identity()));
            for (Entry entry : page) {
                CustomerProductInfo product = visible.get(entry.productId());
                if (product != null && items.size() < limit) {
                    items.add(new WeeklyRankingInfo.Item(entry.rank(), entry.score(), product));
                }
            }
            if (page.size() < limit) {
                break;
            }
            offset += page.size();
        }
        return items;
    }

    private static LocalDate parseMonday(String value) {
        LocalDate date;
        try {
            date = LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new CoreException(ErrorType.BAD_REQUEST, "periodStart는 yyyy-MM-dd 형식이어야 합니다.");
        }
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new CoreException(ErrorType.BAD_REQUEST, "periodStart는 월요일이어야 합니다(UTC 주간 시작).");
        }
        return date;
    }
}
