package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingKey;
import com.loopers.batch.job.ranking.RankingPeriod;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.PERIOD_START;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertRevision;
import static com.loopers.batch.job.ranking.WeeklyRankingTestSupport.insertScore;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
class FrozenScoreReaderTest {

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

    @DisplayName("계산 중인 회차의 점수를 점수 내림차순, 상품 ID 오름차순으로 읽고 읽은 순번을 붙인다. 동점이 페이지 경계에 걸려도 순서가 같다.")
    @Test
    void readsInRankOrderAcrossPages() throws Exception {
        insertRevision(jdbc, PERIOD_START, 1, "CALCULATING");
        insertScore(jdbc, PERIOD_START, 1, 7, 5, null);
        insertScore(jdbc, PERIOD_START, 1, 8, 1, null);
        insertScore(jdbc, PERIOD_START, 1, 9, 5, null);
        insertScore(jdbc, PERIOD_START, 1, 10, 5, null);
        insertScore(jdbc, PERIOD_START, 1, 11, 1, null);

        List<FrozenScore> read = readAll(1, 2);

        assertThat(read).extracting(FrozenScore::productId).containsExactly(7L, 9L, 10L, 8L, 11L);
        assertThat(read).extracting(FrozenScore::itemCount).containsExactly(1, 2, 3, 4, 5);
    }

    @DisplayName("완성되거나 닫힌 회차의 점수는 읽지 않고, 다른 회차의 점수도 섞이지 않는다.")
    @Test
    void readsOnlyCalculatingRevision() throws Exception {
        insertRevision(jdbc, PERIOD_START, 1, "COMPLETED");
        insertScore(jdbc, PERIOD_START, 1, 7, 5, 1);
        insertRevision(jdbc, PERIOD_START, 2, "CANCELLED");
        insertScore(jdbc, PERIOD_START, 2, 7, 5, null);
        insertRevision(jdbc, PERIOD_START, 3, "CALCULATING");
        insertScore(jdbc, PERIOD_START, 3, 9, 7, null);

        assertThat(readAll(1, 2)).isEmpty();
        assertThat(readAll(2, 2)).isEmpty();
        assertThat(readAll(3, 2)).extracting(FrozenScore::productId).containsExactly(9L);
    }

    private List<FrozenScore> readAll(int revision, int pageSize) throws Exception {
        WeeklyRankingKey key = new WeeklyRankingKey(RankingPeriod.parse(PERIOD_START), revision);
        JdbcPagingItemReader<FrozenScore> reader = FrozenScoreReader.create(dataSource, key, pageSize);
        List<FrozenScore> read = new ArrayList<>();
        reader.open(new ExecutionContext());
        try {
            for (FrozenScore score = reader.read(); score != null; score = reader.read()) {
                read.add(score);
            }
        } finally {
            reader.close();
        }
        return read;
    }
}
