package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingRepository;
import com.loopers.batch.support.MutableClock;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.PERIOD_START;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.calculatedAt;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.count;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertActivity;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertFixture;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.revisionRow;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.runTasklet;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.scores;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.setStatus;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.softDeleteActivity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
class FreezeWeeklyScoresTaskletTest {

    private static final Instant NOW = Instant.parse("2026-08-24T06:00:00Z");
    // 상품 ID, 점수, 순위. 순위는 아직 매기지 않았다.
    private static final List<List<Long>> FROZEN_SCORES = List.of(
        Arrays.asList(7L, 5L, null), Arrays.asList(8L, 1L, null), Arrays.asList(9L, 5L, null));

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW, ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    private FreezeWeeklyScoresTasklet tasklet;

    @MockitoSpyBean
    private WeeklyRankingRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private MutableClock clock;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        databaseCleanUp.truncateAllTables();
        clock.set(NOW);
        jdbc = new JdbcTemplate(dataSource);
    }

    @DisplayName("상품별 수량 합을 순위 없이 넣고, 회차를 계산 중으로 남긴다.")
    @Test
    void freezesScores() {
        insertFixture(jdbc);

        run("1", 101L);

        assertThat(scores(jdbc, PERIOD_START, 1)).isEqualTo(FROZEN_SCORES);
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "CALCULATING")
            .containsEntry("result_checksum", null)
            .containsEntry("job_execution_id", 101L);
    }

    @DisplayName("계산 시각은 점수를 넣은 뒤에 읽은 시각이고 UTC 날짜·시각으로 저장한다.")
    @Test
    void readsCalculatedAtAfterScores() {
        insertFixture(jdbc);
        // 점수를 넣는 순간 시계를 1분 옮긴다. 넣은 뒤에 읽어야 옮긴 시각이 저장된다.
        doAnswer(invocation -> {
            Object rows = invocation.callRealMethod();
            clock.set(NOW.plusSeconds(60));
            return rows;
        }).when(repository).insertScores(any(), anyInt());

        run("1", 101L);

        assertThat(calculatedAt(jdbc, PERIOD_START, 1)).isEqualTo(LocalDateTime.parse("2026-08-24T06:01:00"));
    }

    @DisplayName("기간의 시작 순간은 넣고, 다음 월요일 0시와 앞 주의 마지막 순간은 넣지 않는다.")
    @Test
    void includesStartAndExcludesEnd() {
        insertActivity(jdbc, 1, 1, 1, "2026-08-16T23:59:59.999999");
        insertActivity(jdbc, 2, 2, 1, "2026-08-17T00:00:00");
        insertActivity(jdbc, 3, 3, 1, "2026-08-23T23:59:59.999999");
        insertActivity(jdbc, 4, 4, 1, "2026-08-24T00:00:00");

        run("1", 101L);

        assertThat(scores(jdbc, PERIOD_START, 1)).extracting(row -> row.get(0)).containsExactly(2L, 3L);
    }

    @DisplayName("구매가 없는 기간도 점수 0행으로 계산 중 회차를 남긴다.")
    @Test
    void freezesEmptyPeriod() {
        run("1", 101L);

        assertThat(scores(jdbc, PERIOD_START, 1)).isEmpty();
        assertThat(revisionRow(jdbc, PERIOD_START, 1)).containsEntry("status", "CALCULATING");
    }

    @DisplayName("기간 원본에 수량 0인 활동이 있으면 실패하고 아무것도 남기지 않는다. 그 활동을 논리 삭제하면 다시 실행이 성공한다.")
    @Test
    void failsOnInvalidActivity() {
        insertFixture(jdbc);
        insertActivity(jdbc, 45, 9, 0, "2026-08-20T01:00:00");

        assertThatThrownBy(() -> run("1", 101L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("수량");
        assertThat(count(jdbc, "weekly_ranking_scores")).isZero();
        assertThat(count(jdbc, "weekly_ranking_revisions")).isZero();

        softDeleteActivity(jdbc, 45, 9);
        run("1", 102L);

        assertThat(scores(jdbc, PERIOD_START, 1)).isEqualTo(FROZEN_SCORES);
    }

    @DisplayName("논리 삭제된 활동은 점수에 넣지 않는다.")
    @Test
    void skipsSoftDeletedActivity() {
        insertFixture(jdbc);
        insertActivity(jdbc, 45, 9, 3, "2026-08-20T01:00:00");
        softDeleteActivity(jdbc, 45, 9);

        run("1", 101L);

        assertThat(scores(jdbc, PERIOD_START, 1)).isEqualTo(FROZEN_SCORES);
    }

    @DisplayName("계산 중인 회차로 다시 실행하면 고정한 점수를 그대로 두고 아무것도 쓰지 않는다.")
    @Test
    void keepsFrozenScoresOfCalculatingRevision() {
        insertFixture(jdbc);
        run("1", 101L);
        insertActivity(jdbc, 45, 9, 10, "2026-08-20T01:00:00");
        clock.set(NOW.plusSeconds(3600));

        run("1", 102L);

        assertThat(scores(jdbc, PERIOD_START, 1)).isEqualTo(FROZEN_SCORES);
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "CALCULATING")
            .containsEntry("job_execution_id", 101L);
        assertThat(calculatedAt(jdbc, PERIOD_START, 1)).isEqualTo(LocalDateTime.parse("2026-08-24T06:00:00"));
    }

    @DisplayName("완성된 회차로 다시 실행하면 아무것도 쓰지 않는다.")
    @Test
    void leavesCompletedRevisionAsIs() {
        insertFixture(jdbc);
        run("1", 101L);
        setStatus(jdbc, PERIOD_START, 1, "COMPLETED");
        insertActivity(jdbc, 45, 9, 10, "2026-08-20T01:00:00");

        run("1", 102L);

        assertThat(scores(jdbc, PERIOD_START, 1)).isEqualTo(FROZEN_SCORES);
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "COMPLETED")
            .containsEntry("job_execution_id", 101L);
    }

    @DisplayName("닫은 회차로 실행하면 실패하고 아무것도 바꾸지 않는다.")
    @Test
    void failsOnCancelledRevision() {
        insertFixture(jdbc);
        run("1", 101L);
        setStatus(jdbc, PERIOD_START, 1, "CANCELLED");

        assertThatThrownBy(() -> run("1", 102L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("닫은 회차");
        assertThat(revisionRow(jdbc, PERIOD_START, 1)).containsEntry("status", "CANCELLED");
        assertThat(scores(jdbc, PERIOD_START, 1)).isEqualTo(FROZEN_SCORES);
    }

    @DisplayName("새 회차는 그 기간의 마지막 회차 + 1이어야 한다. 닫은 회차도 번호에 포함한다.")
    @Test
    void revisionFollowsLast() {
        insertFixture(jdbc);

        assertThatThrownBy(() -> run("2", 101L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("마지막 회차");
        run("1", 102L);
        setStatus(jdbc, PERIOD_START, 1, "CANCELLED");
        assertThatThrownBy(() -> run("3", 103L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("마지막 회차");
        run("2", 104L);

        assertThat(jdbc.queryForList("SELECT revision FROM weekly_ranking_revisions ORDER BY revision", Integer.class))
            .isEqualTo(List.of(1, 2));
    }

    @DisplayName("그 기간에 계산 중인 회차가 있으면 새 회차는 실패한다. 그 회차를 닫은 뒤에는 시작한다.")
    @Test
    void waitsForCalculatingRevision() {
        insertFixture(jdbc);
        run("1", 101L);

        assertThatThrownBy(() -> run("2", 102L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("계산 중인 회차");
        assertThat(scores(jdbc, PERIOD_START, 2)).isEmpty();

        setStatus(jdbc, PERIOD_START, 1, "CANCELLED");
        run("2", 103L);

        assertThat(revisionRow(jdbc, PERIOD_START, 2)).containsEntry("status", "CALCULATING");
    }

    private void run(String revision, long jobExecutionId) {
        runTasklet(tasklet, transactionManager, TransactionDefinition.ISOLATION_READ_COMMITTED, PERIOD_START, revision, jobExecutionId);
    }
}
