package com.loopers.batch.job.ranking.step;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.PERIOD_START;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertRevision;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertScore;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.parameters;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.scores;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
class ClearRanksListenerTest {

    @Autowired
    private ClearRanksListener listener;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        databaseCleanUp.truncateAllTables();
        jdbc = new JdbcTemplate(dataSource);
    }

    @DisplayName("순위 Step이 시작할 때 계산 중인 그 회차의 순위를 비운다.")
    @Test
    void clearsRanksOfCalculatingRevision() {
        insertRevision(jdbc, PERIOD_START, 1, "CALCULATING");
        insertScore(jdbc, PERIOD_START, 1, 7, 5, 2);
        insertScore(jdbc, PERIOD_START, 1, 8, 1, 1);

        beforeStep("1");

        assertThat(scores(jdbc, PERIOD_START, 1)).extracting(row -> row.get(2)).containsOnlyNulls();
    }

    @DisplayName("완성되거나 닫힌 회차와 다른 회차의 순위는 건드리지 않는다.")
    @Test
    void keepsRanksOfOtherRevisions() {
        insertRevision(jdbc, PERIOD_START, 1, "COMPLETED");
        insertScore(jdbc, PERIOD_START, 1, 7, 5, 1);
        insertRevision(jdbc, PERIOD_START, 2, "CANCELLED");
        insertScore(jdbc, PERIOD_START, 2, 7, 5, 1);
        insertRevision(jdbc, PERIOD_START, 3, "CALCULATING");
        insertScore(jdbc, PERIOD_START, 3, 7, 5, 1);

        beforeStep("1");
        beforeStep("2");

        assertThat(scores(jdbc, PERIOD_START, 1)).extracting(row -> row.get(2)).containsExactly(1L);
        assertThat(scores(jdbc, PERIOD_START, 2)).extracting(row -> row.get(2)).containsExactly(1L);
        assertThat(scores(jdbc, PERIOD_START, 3)).extracting(row -> row.get(2)).containsExactly(1L);
    }

    private void beforeStep(String revision) {
        listener.beforeStep(MetaDataInstanceFactory.createStepExecution(parameters(PERIOD_START, revision)));
    }
}
