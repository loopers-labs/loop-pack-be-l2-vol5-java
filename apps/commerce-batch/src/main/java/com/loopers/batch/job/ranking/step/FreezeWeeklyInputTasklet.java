package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.domain.ranking.RankingChecksum;
import com.loopers.domain.ranking.WeeklySnapshotId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Step 1 "freezeWeeklyInputStep" — 그 주의 구매 활동을 snapshot 입력으로 복사하고 회차 행을 만든다 (설계 D9).
 *
 * <p>트랜잭션 요구 (Step 빌더가 지정, ⑤ WeeklyRankingJobConfig):
 * <ul>
 *   <li>실제 {@code PlatformTransactionManager}(JpaTransactionManager). ResourcelessTransactionManager 금지.</li>
 *   <li>isolation READ_COMMITTED — MySQL 기본 REPEATABLE READ 에서는 {@code INSERT ... SELECT} 가 원본
 *       product_purchase_activity 행에 공유 락을 걸어 consumer 의 구매 활동 저장을 막을 수 있다.</li>
 * </ul>
 * 아래 1~7 은 모두 그 TX 하나 안에서 실행된다. 예외는 잡지 않고 던져 Step FAILED → 전체 rollback (반쪽 snapshot 없음).
 */
@Slf4j
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Component
public class FreezeWeeklyInputTasklet implements Tasklet {

    public static final String STEP_NAME = "freezeWeeklyInputStep";
    private static final String STATUS_CALCULATING = "CALCULATING";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Value("#{jobParameters['periodStart']}")
    private String periodStart;

    @Value("#{jobParameters['snapshotId']}")
    private String snapshotId;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // 1. 파라미터 검증 (D1). 1차는 Job 의 JobParametersValidator, 2차는 여기 (Tasklet 단독 사용 대비 2중 방어)
        WeeklySnapshotId snapshot = WeeklySnapshotId.of(snapshotId, periodStart);

        // 2. 충돌 검사 (D6) — 같은 snapshot_id 또는 같은 (period_start, revision) 이 있으면 덮어쓰지 않고 실패
        Integer conflicts = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM weekly_ranking_revisions WHERE snapshot_id = ? OR (period_start = ? AND revision = ?)",
            Integer.class, snapshot.value(), snapshot.periodStart(), snapshot.revision()
        );
        if (conflicts != null && conflicts > 0) {
            throw new IllegalStateException("conflict: 이미 존재하는 회차입니다. snapshotId=" + snapshot.value()
                + ", periodStart=" + snapshot.periodStart() + ", revision=" + snapshot.revision());
        }

        // 3. 고정 시각 (D12: UTC 벽시계로 바인딩)
        LocalDateTime frozenAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        // 4. 원본 → 입력 복사. 시작 포함·끝 제외. max(id) 로 범위를 고정하지 않는다 (D9)
        jdbcTemplate.update(
            "INSERT INTO weekly_ranking_inputs "
                + "(snapshot_id, order_id, product_id, quantity, confirmed_at, created_at, updated_at) "
                + "SELECT ?, order_id, product_id, quantity, confirmed_at, ?, ? "
                + "FROM product_purchase_activity "
                + "WHERE confirmed_at >= ? AND confirmed_at < ? AND deleted_at IS NULL",
            snapshot.value(), frozenAt, frozenAt, snapshot.periodStartUtc(), snapshot.periodEndUtc()
        );

        // 5. 복사본을 같은 TX 에서 다시 읽어 건수·checksum (D13). confirmed_at 은 LocalDateTime 으로 읽어 시간대 변환을 피한다
        List<RankingChecksum.InputRow> rows = jdbcTemplate.query(
            "SELECT order_id, product_id, quantity, confirmed_at FROM weekly_ranking_inputs "
                + "WHERE snapshot_id = ? ORDER BY order_id, product_id",
            (rs, rowNum) -> new RankingChecksum.InputRow(
                rs.getLong("order_id"), rs.getLong("product_id"), rs.getInt("quantity"),
                rs.getObject("confirmed_at", LocalDateTime.class)
            ),
            snapshot.value()
        );
        String inputChecksum = RankingChecksum.inputChecksum(rows);

        // 6. 기대 상품 수 (Step 3 완성 검증 기준, D3)
        Integer expectedProductCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT product_id) FROM weekly_ranking_inputs WHERE snapshot_id = ?",
            Integer.class, snapshot.value()
        );

        // 7. 회차 행 (0행이어도 생성 — 구매 0건 완성본 경로)
        jdbcTemplate.update(
            "INSERT INTO weekly_ranking_revisions "
                + "(snapshot_id, period_start, revision, status, frozen_at, input_count, expected_product_count, "
                + "input_checksum, result_checksum, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)",
            snapshot.value(), snapshot.periodStart(), snapshot.revision(), STATUS_CALCULATING, frozenAt,
            rows.size(), expectedProductCount, inputChecksum, frozenAt, frozenAt
        );

        log.info("입력 고정 완료 snapshotId={} period=[{}, {}) inputCount={} expectedProductCount={} inputChecksum={}…",
            snapshot.value(), snapshot.periodStartUtc(), snapshot.periodEndUtc(), rows.size(), expectedProductCount,
            inputChecksum.substring(0, 12));
        return RepeatStatus.FINISHED;
    }
}
