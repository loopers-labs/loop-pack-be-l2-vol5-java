package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.ProductRank;
import com.loopers.batch.job.ranking.RankingPeriod;
import com.loopers.batch.job.ranking.WeeklyRankingKey;
import com.loopers.batch.job.ranking.WeeklyRankingRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.PERIOD_START;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertRevision;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertScore;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.scores;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
class ScoreRankWriterTest {

    @Autowired
    private WeeklyRankingRepository repository;

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
        insertScore(jdbc, PERIOD_START, 1, 7, 5, null);
        insertScore(jdbc, PERIOD_START, 1, 8, 1, null);
        insertScore(jdbc, PERIOD_START, 1, 9, 5, null);
    }

    @DisplayName("순위를 절대값으로 쓴다. 같은 묶음을 두 번 써도 같은 값이 된다.")
    @Test
    void writesAbsoluteRanks() throws Exception {
        Chunk<ProductRank> chunk = Chunk.of(new ProductRank(7, 1), new ProductRank(9, 2));

        writer(1).write(chunk);
        writer(1).write(chunk);

        assertThat(scores(jdbc, PERIOD_START, 1)).isEqualTo(List.of(
            Arrays.asList(7L, 5L, 1L), Arrays.asList(8L, 1L, null), Arrays.asList(9L, 5L, 2L)));
    }

    // rewriteBatchedStatements가 켜져 있으면 드라이버는 4개 이상인 batch를 여러 문장으로 묶어 보낸다.
    @DisplayName("묶음에 아이템이 4개 이상이어도 바뀐 행 수를 정확히 세어 모두 쓴다.")
    @Test
    void countsRowsOfLargeChunk() throws Exception {
        insertScore(jdbc, PERIOD_START, 1, 10, 4, null);
        insertScore(jdbc, PERIOD_START, 1, 11, 3, null);

        writer(1).write(Chunk.of(new ProductRank(7, 1), new ProductRank(9, 2), new ProductRank(10, 3),
            new ProductRank(11, 4), new ProductRank(8, 5)));

        assertThat(scores(jdbc, PERIOD_START, 1)).extracting(row -> row.get(2)).containsExactly(1L, 5L, 2L, 3L, 4L);
    }

    @DisplayName("계산 중이 아닌 회차에는 쓰지 않고 실패한다.")
    @Test
    void failsOnClosedRevision() {
        jdbc.update("UPDATE weekly_ranking_revisions SET status = 'CANCELLED'");

        assertThatThrownBy(() -> writer(1).write(Chunk.of(new ProductRank(7, 1), new ProductRank(9, 2))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("계산 중인 회차");
        assertThat(scores(jdbc, PERIOD_START, 1)).extracting(row -> row.get(2)).containsOnlyNulls();
    }

    @DisplayName("같은 회차에서 순위가 겹치면 유일 제약 오류가 그대로 올라온다.")
    @Test
    void surfacesDuplicateRank() throws Exception {
        writer(1).write(Chunk.of(new ProductRank(7, 1)));

        assertThatThrownBy(() -> writer(1).write(Chunk.of(new ProductRank(9, 1))))
            .isInstanceOf(DuplicateKeyException.class);
    }

    private ScoreRankWriter writer(int revision) {
        return new ScoreRankWriter(repository, new WeeklyRankingKey(RankingPeriod.parse(PERIOD_START), revision));
    }
}
