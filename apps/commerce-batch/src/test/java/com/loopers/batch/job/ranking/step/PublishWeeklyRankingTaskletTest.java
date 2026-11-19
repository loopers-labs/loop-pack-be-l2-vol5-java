package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.EMPTY_CHECKSUM;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.EXPECTED_RANKING;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.PERIOD_START;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.RESULT_CHECKSUM;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertRevision;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertScore;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.ranking;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.revisionRow;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.runTasklet;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.setStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
class PublishWeeklyRankingTaskletTest {

    @Autowired
    private PublishWeeklyRankingTasklet tasklet;

    @MockitoSpyBean
    private WeeklyRankingRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        databaseCleanUp.truncateAllTables();
        jdbc = new JdbcTemplate(dataSource);
        insertRevision(jdbc, PERIOD_START, 1, "CALCULATING");
    }

    @DisplayName("순위가 빠짐없으면 결과 checksum을 채워 회차를 완성한다.")
    @Test
    void completesRevision() {
        insertRankedFixture(1, 2, 3);

        run();

        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "COMPLETED")
            .containsEntry("result_checksum", RESULT_CHECKSUM);
        assertThat(ranking(jdbc, PERIOD_START, 1)).isEqualTo(EXPECTED_RANKING);
    }

    @DisplayName("점수가 0행인 회차도 완성한다. checksum은 빈 문자열의 SHA-256이다.")
    @Test
    void completesEmptyRevision() {
        run();

        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "COMPLETED")
            .containsEntry("result_checksum", EMPTY_CHECKSUM);
    }

    @DisplayName("이미 완성된 회차로 다시 실행하면 아무것도 바꾸지 않는다.")
    @Test
    void leavesCompletedRevisionAsIs() {
        insertRankedFixture(1, 2, 3);
        run();

        run();

        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "COMPLETED")
            .containsEntry("result_checksum", RESULT_CHECKSUM);
    }

    @DisplayName("닫은 회차는 공개하지 않고 실패한다.")
    @Test
    void failsOnCancelledRevision() {
        insertRankedFixture(1, 2, 3);
        setStatus(jdbc, PERIOD_START, 1, "CANCELLED");

        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("닫은 회차");
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "CANCELLED")
            .containsEntry("result_checksum", null);
    }

    @DisplayName("순위가 빈 점수가 있으면 실패하고 회차는 계산 중으로 남는다.")
    @Test
    void failsOnMissingRank() {
        insertRankedFixture(1, 2, null);

        assertIncompleteRanksFail();
    }

    @DisplayName("순위가 1부터 시작하지 않으면 실패한다.")
    @Test
    void failsWhenRanksDoNotStartAtOne() {
        insertRankedFixture(2, 3, 4);

        assertIncompleteRanksFail();
    }

    @DisplayName("순위가 중간에 끊기면 실패한다.")
    @Test
    void failsOnRankGap() {
        insertRankedFixture(1, 2, 4);

        assertIncompleteRanksFail();
    }

    @DisplayName("checksum을 만든 뒤 완성하기 전에 다른 연결이 회차를 닫으면 실패하고 닫힌 상태가 남는다.")
    @Test
    void failsWhenClosedBeforeCompleting() {
        insertRankedFixture(1, 2, 3);
        doAnswer(invocation -> {
            Object checksum = invocation.callRealMethod();
            closeOnAnotherConnection();
            return checksum;
        }).when(repository).resultChecksum(any(), anyInt());

        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("계산 중");
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "CANCELLED")
            .containsEntry("result_checksum", null);
    }

    // 공개 트랜잭션에 묶이지 않도록 풀에서 새 연결을 받아 운영자처럼 닫는다.
    private void closeOnAnotherConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE weekly_ranking_revisions SET status = 'CANCELLED', cancelled_at = ?, cancel_reason = ?
                 WHERE period_start = ? AND revision = ? AND status IN ('CALCULATING', 'COMPLETED')""")) {
            statement.setObject(1, LocalDateTime.parse("2026-08-25T00:00:00"));
            statement.setString(2, "공개 직전 닫기");
            statement.setObject(3, LocalDate.parse(PERIOD_START));
            statement.setInt(4, 1);
            statement.executeUpdate();
        }
    }

    private void assertIncompleteRanksFail() {
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("순위");
        assertThat(revisionRow(jdbc, PERIOD_START, 1))
            .containsEntry("status", "CALCULATING")
            .containsEntry("result_checksum", null);
    }

    // fixture의 점수에 순위를 붙여 넣는다. 상품 7, 9, 8 순서다.
    private void insertRankedFixture(Integer rank7, Integer rank9, Integer rank8) {
        insertScore(jdbc, PERIOD_START, 1, 7, 5, rank7);
        insertScore(jdbc, PERIOD_START, 1, 9, 5, rank9);
        insertScore(jdbc, PERIOD_START, 1, 8, 1, rank8);
    }

    private void run() {
        runTasklet(tasklet, transactionManager, TransactionDefinition.ISOLATION_DEFAULT, PERIOD_START, "1", 101L);
    }
}
