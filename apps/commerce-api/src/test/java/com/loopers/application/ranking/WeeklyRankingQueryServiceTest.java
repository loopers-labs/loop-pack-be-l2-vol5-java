package com.loopers.application.ranking;

import com.loopers.domain.ranking.PublishedRanking;
import com.loopers.domain.ranking.PublishedRankingRepository;
import com.loopers.domain.ranking.RankingCandidate;
import com.loopers.domain.ranking.VisibleRankingProducts;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeeklyRankingQueryServiceTest {
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 17);
    private final PublishedRankingRepository repository = mock(PublishedRankingRepository.class);
    private final VisibleRankingProducts products = mock(VisibleRankingProducts.class);
    private final WeeklyRankingReader reader = new WeeklyRankingReader(repository, products);
    private final WeeklyRankingQueryService service = new WeeklyRankingQueryService(repository, reader);

    @Test
    void filtersBeforeLimitAndKeepsOriginalRanksScoresAndTheSelectedSnapshotAcrossPages() {
        when(repository.findPublished(WEEK)).thenReturn(Optional.of(snapshot(4)));
        when(repository.findCandidates(eq("weekly-20260817-r1"), eq(0L), anyInt()))
            .thenReturn(List.of(new RankingCandidate(1, 8, 1), new RankingCandidate(2, 7, 2)));
        when(repository.findCandidates(eq("weekly-20260817-r1"), eq(2L), anyInt()))
            .thenReturn(List.of(new RankingCandidate(3, 6, 3), new RankingCandidate(4, 5, 4)));
        when(products.names(List.of(1L, 2L))).thenReturn(Map.of(2L, "현재 이름"));
        when(products.names(List.of(3L, 4L))).thenReturn(Map.of(4L, "재고 0 상품"));

        var result = service.query(WEEK, 2);

        assertThat(result.status()).isEqualTo("PUBLISHED");
        assertThat(result.snapshotId()).isEqualTo("weekly-20260817-r1");
        assertThat(result.frozenAt()).isEqualTo(Instant.parse("2026-08-24T00:05:00.123456789Z"));
        assertThat(result.items()).containsExactly(new WeeklyRankingInfo.Item(2, "현재 이름", 7, 2),
            new WeeklyRankingInfo.Item(4, "재고 0 상품", 5, 4));
        verify(repository).findPublished(WEEK);
    }

    @Test
    void emptyPublishedSnapshotIsSuccessWithoutQueryingCurrentProducts() {
        when(repository.findPublished(WEEK)).thenReturn(Optional.of(snapshot(0)));
        assertThat(service.query(WEEK, 10).items()).isEmpty();
        verifyNoInteractions(products);
    }

    @Test
    void missingPublicationIsNotAnEmptyCompletedSnapshot() {
        when(repository.findPublished(WEEK)).thenReturn(Optional.empty());
        assertReason(WeeklyRankingQueryException.Reason.NOT_PUBLISHED);
        verifyNoInteractions(products);
    }

    @Test
    void connectionFailureIsUnavailableWithoutServingAnyOtherSnapshot() {
        when(repository.findPublished(WEEK)).thenThrow(new DataAccessResourceFailureException("owned DB unavailable"));
        assertReason(WeeklyRankingQueryException.Reason.UNAVAILABLE);
        verifyNoInteractions(products);
    }

    @Test
    void currentProductStoreConnectionFailureAlsoDiscardsTheWholeResponse() {
        when(repository.findPublished(WEEK)).thenReturn(Optional.of(snapshot(1)));
        when(repository.findCandidates(eq("weekly-20260817-r1"), eq(0L), anyInt()))
            .thenReturn(List.of(new RankingCandidate(1, 5, 1)));
        when(products.names(List.of(1L))).thenThrow(new DataAccessResourceFailureException("main DB unavailable"));
        assertReason(WeeklyRankingQueryException.Reason.UNAVAILABLE);
    }

    @Test
    void schemaOrSqlDefectsAreNotDisguisedAsAnAvailabilityFailure() {
        var brokenSql = new BadSqlGrammarException("ranking read", "test statement", new SQLException("missing schema", "42S02"));
        when(repository.findPublished(WEEK)).thenThrow(brokenSql);
        assertThatThrownBy(() -> service.query(WEEK, 10)).isSameAs(brokenSql);
    }

    @Test
    void missingResultRowsAreCorruptionRatherThanPartialSuccess() {
        when(repository.findPublished(WEEK)).thenReturn(Optional.of(snapshot(4)));
        when(repository.findCandidates(eq("weekly-20260817-r1"), anyLong(), anyInt())).thenReturn(List.of());
        assertReason(WeeklyRankingQueryException.Reason.CORRUPT);
    }

    private void assertReason(WeeklyRankingQueryException.Reason reason) {
        assertThatThrownBy(() -> service.query(WEEK, 10)).isInstanceOfSatisfying(WeeklyRankingQueryException.class,
            failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private PublishedRanking snapshot(long count) {
        return new PublishedRanking("weekly-20260817-r1", WEEK, WEEK.plusDays(7), 1,
            Instant.parse("2026-08-24T00:05:00.123456789Z"), count);
    }
}
