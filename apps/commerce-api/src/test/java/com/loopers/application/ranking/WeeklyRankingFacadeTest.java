package com.loopers.application.ranking;

import com.loopers.domain.ranking.PublishedRevision;
import com.loopers.domain.ranking.RankedProduct;
import com.loopers.domain.ranking.WeeklyRankingReader;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyRankingFacadeTest {

    private static final LocalDate MONDAY = LocalDate.parse("2026-08-17");
    private static final Instant CALCULATED_AT = Instant.parse("2026-08-24T06:00:00Z");

    private final FakeReader reader = new FakeReader();
    private final WeeklyRankingFacade facade = new WeeklyRankingFacade(reader);

    @DisplayName("완성된 회차가 없으면 NOT_PUBLISHED이고 회차 값은 비어 있으며, 순위를 읽지 않는다.")
    @Test
    void notPublishedDoesNotReadItems() {
        WeeklyRankingInfo info = facade.getWeeklyRanking(MONDAY, 10);

        assertThat(info.status()).isEqualTo(WeeklyRankingStatus.NOT_PUBLISHED);
        assertThat(info.periodStart()).isEqualTo(MONDAY);
        assertThat(info.periodEnd()).isEqualTo(LocalDate.parse("2026-08-24"));
        assertThat(info.revision()).isNull();
        assertThat(info.calculatedAt()).isNull();
        assertThat(info.items()).isEmpty();
        assertThat(reader.itemReads).isEmpty();
    }

    @DisplayName("완성된 회차가 있으면 처음 찾은 회차와 limit으로 순위를 읽어 PUBLISHED로 돌려준다.")
    @Test
    void publishedReadsItemsOfThatRevision() {
        RankedProduct first = new RankedProduct(1, 5, 7, "상품 7", 12_000, 3, "브랜드 3");
        reader.published.put(MONDAY, new PublishedRevision(2, CALCULATED_AT));
        reader.items.put(2, List.of(first));

        WeeklyRankingInfo info = facade.getWeeklyRanking(MONDAY, 5);

        assertThat(info.status()).isEqualTo(WeeklyRankingStatus.PUBLISHED);
        assertThat(info.revision()).isEqualTo(2);
        assertThat(info.calculatedAt()).isEqualTo(CALCULATED_AT);
        assertThat(info.items()).containsExactly(first);
        assertThat(reader.itemReads).containsExactly(List.of(MONDAY, 2, 5));
    }

    @DisplayName("limit이 1~100 밖이면 잘못된 요청으로 거절하고 아무것도 읽지 않는다.")
    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void rejectsLimitOutOfRange(int limit) {
        assertThatThrownBy(() -> facade.getWeeklyRanking(MONDAY, limit))
            .isInstanceOfSatisfying(CoreException.class,
                e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        assertThat(reader.publishedReads).isZero();
    }

    @DisplayName("기간 시작이 월요일이 아니면 잘못된 요청으로 거절하고 아무것도 읽지 않는다.")
    @Test
    void rejectsNonMonday() {
        assertThatThrownBy(() -> facade.getWeeklyRanking(LocalDate.parse("2026-08-19"), 10))
            .isInstanceOf(CoreException.class);
        assertThat(reader.publishedReads).isZero();
    }

    private static final class FakeReader implements WeeklyRankingReader {

        private final Map<LocalDate, PublishedRevision> published = new HashMap<>();
        private final Map<Integer, List<RankedProduct>> items = new HashMap<>();
        private final List<List<Object>> itemReads = new ArrayList<>();
        private int publishedReads;

        @Override
        public Optional<PublishedRevision> findPublished(LocalDate periodStart) {
            publishedReads++;
            return Optional.ofNullable(published.get(periodStart));
        }

        @Override
        public List<RankedProduct> findRankedProducts(LocalDate periodStart, int revision, int limit) {
            itemReads.add(List.of(periodStart, revision, limit));
            return items.getOrDefault(revision, List.of());
        }
    }
}
