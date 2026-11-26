package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.domain.ranking.RankingChecksum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Step 3 "openWeeklyRankingStep" — 순위를 매기고 회차를 COMPLETED 로 바꾸고 공개 pointer 를 조건부로 갱신한다 (설계 D11).
 *
 * <p>아래 1~6 전부가 Step TX 하나(JpaTransactionManager) 안이다. 어느 단계에서 예외가 나도 ranking·status·pointer 전부
 * rollback 된다 (R3). 재실행하면 같은 ranking·같은 checksum·같은 pointer 로 수렴한다 (R6). Step 조립·TX 속성은 ⑤ WeeklyRankingJobConfig.
 * 더 새 revision 이 이미 공개돼 있으면 pointer 는 건드리지 않고 Job 은 COMPLETED 로 끝난다 (D8).
 */
@Slf4j
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Component
public class OpenWeeklyRankingTasklet implements Tasklet {

    public static final String STEP_NAME = "openWeeklyRankingStep";
    private static final String STATUS_COMPLETED = "COMPLETED";

    /** 1. 회차 행 잠금 조회. 같은 snapshot 의 공개 단계가 겹치지 않게 직렬화한다 (Batch 의 JobInstance 동시 실행 차단 위의 방어선). */
    static final String LOCK_REVISION_SQL =
        "SELECT period_start, revision, status, expected_product_count, result_checksum "
            + "FROM weekly_ranking_revisions WHERE snapshot_id = ? FOR UPDATE";

    /** 2. 완성 조건: 점수 행 수 == expected_product_count (R4 전반). */
    static final String COUNT_SCORES_SQL =
        "SELECT COUNT(*) FROM weekly_ranking_scores WHERE snapshot_id = ?";

    /** 3. 순위 매기기. 모든 행을 한 문장으로, 정렬 키가 유일(product_id)해 재실행 시 같은 값 (R3·R6). */
    static final String ASSIGN_RANKING_SQL =
        "UPDATE weekly_ranking_scores s "
            + "JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY score DESC, product_id ASC) AS rn "
            + "      FROM weekly_ranking_scores WHERE snapshot_id = ?) r ON s.id = r.id "
            + "SET s.ranking = r.rn, s.updated_at = ?";

    /** 4. 순위 연속성: count == distinct == N, min 1, max N (R4 후반). */
    static final String RANKING_SHAPE_SQL =
        "SELECT COUNT(*) AS cnt, COUNT(DISTINCT ranking) AS distinct_cnt, MIN(ranking) AS min_rank, MAX(ranking) AS max_rank "
            + "FROM weekly_ranking_scores WHERE snapshot_id = ? AND ranking IS NOT NULL";

    /** 5. 결과 checksum 입력. */
    static final String RESULT_ROWS_SQL =
        "SELECT product_id, score, ranking FROM weekly_ranking_scores WHERE snapshot_id = ? ORDER BY ranking";

    /** 5. 회차 COMPLETED. 이미 COMPLETED 여도 같은 값으로 다시 쓴다. */
    static final String COMPLETE_REVISION_SQL =
        "UPDATE weekly_ranking_revisions SET status = ?, result_checksum = ?, updated_at = ? WHERE snapshot_id = ?";

    /**
     * 6. 현재 pointer 의 revision (없으면 0행). 6a 의 UPDATED/UNCHANGED 구분과 6b 거절 판정에 쓴다.
     * FOR SHARE 인 이유: 이 TX 는 2단계 COUNT 에서 REPEATABLE READ 스냅샷이 잡힌다. 그 뒤 다른 TX 가 새 revision 을 공개하면
     * 6a UPDATE(잠금 읽기)는 0행인데 평범한 SELECT 는 옛 스냅샷을 볼 수 있다. 잠금 읽기는 최신 commit 값을 읽어
     * 거절 로그·exitDescription 의 "현재 공개 revision" 이 정확해진다 (설계 D11).
     */
    static final String CURRENT_POINTER_SQL =
        "SELECT revision FROM weekly_ranking_publications WHERE period_start = ? FOR SHARE";

    /**
     * 6a. 조건부 갱신 (D5). revision <= 로 같은 revision 재실행을 통과시키고, 그때 published_at 은 유지한다(CASE).
     * 바인딩: snapshot_id, revision, revision(CASE 비교), now, now, period_start, revision(<=).
     */
    static final String UPDATE_POINTER_SQL =
        "UPDATE weekly_ranking_publications "
            + "SET snapshot_id = ?, revision = ?, "
            + "    published_at = CASE WHEN revision = ? THEN published_at ELSE ? END, "
            + "    updated_at = ? "
            + "WHERE period_start = ? AND revision <= ?";

    /** 6c. 첫 공개. 같은 기간의 첫 INSERT 가 동시에 일어나면 PK 위반으로 한쪽이 실패한다 (직렬 실행 전제, 한계). */
    static final String INSERT_POINTER_SQL =
        "INSERT INTO weekly_ranking_publications (period_start, snapshot_id, revision, published_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Value("#{jobParameters['snapshotId']}")
    private String snapshotId;

    private record Revision(LocalDate periodStart, int revision, String status, int expectedProductCount, String resultChecksum) {}

    private enum PointerResult { INSERTED, UPDATED, UNCHANGED, REJECTED }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        Revision revision = lockRevision();                                   // 1
        int expected = verifyScoreCount(revision);                            // 2
        assignRanking(expected, now);                                         // 3
        verifyRankingShape(expected);                                         // 4
        String resultChecksum = completeRevision(revision, now);              // 5
        PointerResult pointerResult = publishPointer(revision, now, contribution); // 6

        log.info("공개 단계 완료 snapshotId={} products={} resultChecksum={}… pointer={}",
            snapshotId, expected, resultChecksum.substring(0, 12), pointerResult);                      // 7
        return RepeatStatus.FINISHED;
    }

    /** 1. 회차 행 잠금 조회. */
    private Revision lockRevision() {
        List<Revision> rows = jdbcTemplate.query(LOCK_REVISION_SQL, (rs, rowNum) -> new Revision(
            rs.getObject("period_start", LocalDate.class), rs.getInt("revision"), rs.getString("status"),
            rs.getInt("expected_product_count"), rs.getString("result_checksum")
        ), snapshotId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("회차 행이 없습니다. snapshotId=" + snapshotId);
        }
        return rows.get(0);
    }

    /** 2. 완성 조건 검증 (R4 전반). 0 == 0 은 구매 0건 완성본으로 통과. */
    private int verifyScoreCount(Revision revision) {
        Integer count = jdbcTemplate.queryForObject(COUNT_SCORES_SQL, Integer.class, snapshotId);
        int actual = count == null ? 0 : count;
        if (actual != revision.expectedProductCount()) {
            throw new IllegalStateException("점수 행 수가 기대 상품 수와 다릅니다. snapshotId=" + snapshotId
                + ", expectedProductCount=" + revision.expectedProductCount() + ", scoreRows=" + actual);
        }
        return actual;
    }

    /** 3. 순위 매기기. Connector/J 기본(useAffectedRows=false)은 매치된 행 수를 돌려주므로 재실행에서도 N 이다. */
    private void assignRanking(int expected, LocalDateTime now) {
        int updated = jdbcTemplate.update(ASSIGN_RANKING_SQL, snapshotId, now);
        if (updated != expected) {
            throw new IllegalStateException("순위가 매겨진 행 수가 기대 상품 수와 다릅니다. snapshotId=" + snapshotId
                + ", expected=" + expected + ", updated=" + updated);
        }
    }

    /** 4. 순위 연속성 검증 (R4 후반). N == 0 이면 생략. */
    private void verifyRankingShape(int expected) {
        if (expected == 0) {
            return;
        }
        jdbcTemplate.query(RANKING_SHAPE_SQL, rs -> {
            int cnt = rs.getInt("cnt");
            int distinct = rs.getInt("distinct_cnt");
            int min = rs.getInt("min_rank");
            int max = rs.getInt("max_rank");
            if (cnt != expected || distinct != expected || min != 1 || max != expected) {
                throw new IllegalStateException("순위가 1..N 연속이 아닙니다. snapshotId=" + snapshotId + ", expected=" + expected
                    + ", count=" + cnt + ", distinct=" + distinct + ", min=" + min + ", max=" + max);
            }
        }, snapshotId);
    }

    /** 5. 결과 checksum 계산·회차 COMPLETED. 재실행에서 기존 checksum 과 다르면 R6 위반이라 실패. */
    private String completeRevision(Revision revision, LocalDateTime now) {
        List<RankingChecksum.ResultRow> rows = jdbcTemplate.query(RESULT_ROWS_SQL, (rs, rowNum) ->
            new RankingChecksum.ResultRow(rs.getLong("product_id"), rs.getLong("score"), rs.getInt("ranking")), snapshotId);
        String checksum = RankingChecksum.resultChecksum(rows);
        if (revision.resultChecksum() != null && !revision.resultChecksum().equals(checksum)) {
            throw new IllegalStateException("재실행 결과 checksum 이 저장된 값과 다릅니다. snapshotId=" + snapshotId
                + ", stored=" + revision.resultChecksum() + ", now=" + checksum);
        }
        jdbcTemplate.update(COMPLETE_REVISION_SQL, STATUS_COMPLETED, checksum, now, snapshotId);
        return checksum;
    }

    /** 6. 공개 pointer 조건부 갱신 (D5 두 단계). 현재 revision 은 FOR SHARE 로 읽는다(위 상수 주석). 거절은 D8: pointer 미변경, WARN, ExitStatus 설명만 추가. */
    private PointerResult publishPointer(Revision revision, LocalDateTime now, StepContribution contribution) {
        Optional<Integer> current = jdbcTemplate.query(CURRENT_POINTER_SQL,
            (rs, rowNum) -> rs.getInt("revision"), revision.periodStart()).stream().findFirst();

        int updated = jdbcTemplate.update(UPDATE_POINTER_SQL,                                           // 6a
            snapshotId, revision.revision(), revision.revision(), now, now, revision.periodStart(), revision.revision());
        if (updated == 1) {
            return current.filter(r -> r == revision.revision()).isPresent() ? PointerResult.UNCHANGED : PointerResult.UPDATED;
        }
        if (current.isPresent()) {                                                                        // 6b
            int published = current.get();
            log.warn("공개 거절: 더 새 revision 이 이미 공개됨. snapshotId={} revision={} publishedRevision={}",
                snapshotId, revision.revision(), published);
            contribution.setExitStatus(
                ExitStatus.COMPLETED.addExitDescription("NOT_PUBLISHED: newer revision r" + published + " already published"));
            return PointerResult.REJECTED;
        }
        jdbcTemplate.update(INSERT_POINTER_SQL,                                                          // 6c
            revision.periodStart(), snapshotId, revision.revision(), now, now);
        return PointerResult.INSERTED;
    }
}
