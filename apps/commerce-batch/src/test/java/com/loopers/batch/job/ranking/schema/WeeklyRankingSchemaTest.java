package com.loopers.batch.job.ranking.schema;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
class WeeklyRankingSchemaTest {

    private static final LocalDate PERIOD_START = LocalDate.parse("2026-08-17");

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

    @DisplayName("같은 회차에서는 계산 순위가 겹칠 수 없고, 다른 회차는 같은 순위를 쓸 수 있다.")
    @Test
    void rankIsUniqueInRevision() {
        insertScore(1, 7, 5, 1);

        assertThatThrownBy(() -> insertScore(1, 8, 1, 1)).isInstanceOf(DuplicateKeyException.class);
        insertScore(2, 8, 1, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_scores", Integer.class)).isEqualTo(2);
    }

    @DisplayName("같은 회차의 같은 상품은 한 행이다.")
    @Test
    void productIsUniqueInRevision() {
        insertScore(1, 7, 5, 1);

        assertThatThrownBy(() -> insertScore(1, 7, 3, 2)).isInstanceOf(DuplicateKeyException.class);
    }

    @DisplayName("점수가 0 이하면 넣을 수 없다.")
    @Test
    void scoreMustBePositive() {
        assertThatThrownBy(() -> insertScore(1, 7, 0, 1)).hasMessageContaining("ck_weekly_ranking_scores_score");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_scores", Integer.class)).isZero();
    }

    @DisplayName("같은 기간의 같은 회차 정보는 한 행이다.")
    @Test
    void revisionIsUniqueInPeriod() {
        insertRevision(1);

        assertThatThrownBy(() -> insertRevision(1)).isInstanceOf(DuplicateKeyException.class);
    }

    @DisplayName("계산 중인 회차는 결과 checksum 없이 저장된다.")
    @Test
    void calculatingRevisionHasNoChecksum() {
        jdbc.update("""
            INSERT INTO weekly_ranking_revisions (period_start, revision, status, calculated_at, job_execution_id)
            VALUES (?, 1, 'CALCULATING', ?, 1)""", PERIOD_START, LocalDateTime.parse("2026-08-24T06:00:00"));

        assertThat(jdbc.queryForMap("SELECT status, result_checksum FROM weekly_ranking_revisions"))
            .containsEntry("status", "CALCULATING")
            .containsEntry("result_checksum", null);
    }

    @DisplayName("순위를 매기기 전의 점수는 순위 없이 여러 행 저장되고, 비어 있는 순위끼리는 겹친다고 보지 않는다.")
    @Test
    void scoresWithoutRankDoNotConflict() {
        insertScore(1, 7, 5, null);
        insertScore(1, 8, 1, null);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM weekly_ranking_scores WHERE rank_no IS NULL", Integer.class)).isEqualTo(2);
    }

    private void insertScore(int revision, long productId, long score, Integer rank) {
        jdbc.update("""
            INSERT INTO weekly_ranking_scores (period_start, revision, product_id, score, rank_no)
            VALUES (?, ?, ?, ?, ?)""", PERIOD_START, revision, productId, score, rank);
    }

    private void insertRevision(int revision) {
        jdbc.update("""
            INSERT INTO weekly_ranking_revisions
                (period_start, revision, status, calculated_at, result_checksum, job_execution_id)
            VALUES (?, ?, 'COMPLETED', ?, ?, 1)""",
            PERIOD_START, revision, LocalDateTime.parse("2026-08-24T06:00:00"), "0".repeat(64));
    }
}
