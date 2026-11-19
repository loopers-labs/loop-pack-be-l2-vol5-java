package com.loopers.batch.job.ranking.command;

import com.loopers.batch.job.ranking.RankingTables;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.score.ProductKey;
import com.loopers.batch.job.ranking.score.WeeklyScoreWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/** 9주차 D-184 · D-185 · D-191 · D-192. 사람은 기간만 넘기고 revision 은 실행 진입점이 정한다 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "weekly-ranking.chunk-size=2"
})
class WeeklyRankingCommandsTest {

    @Autowired
    private WeeklyRankingCommands commands;

    @Autowired
    private JobExplorer jobExplorer;

    @MockitoSpyBean
    private WeeklyScoreWriter writer;

    @Autowired
    private RankingTables tables;

    @AfterEach
    void tearDown() {
        tables.truncate();
    }

    @DisplayName("기간만 넘기면 처음은 r1, 공개 뒤 다시 실행하면 r2 를 공개하고, 내려간 r1 은 다음 번호 판단에 쓰지 않는다.")
    @Test
    void choosesNextRevisionAfterPublished() throws Exception {
        // arrange
        LocalDate week = LocalDate.parse("2026-12-07");
        tables.confirmLabFixture(week);

        // act
        commands.run(week);
        commands.run(week);

        // assert
        assertAll(
            () -> assertThat(tables.manifestOf("weekly-20261207-r1")).containsEntry("status", "COMPLETED"),
            () -> assertThat(tables.manifestOf("weekly-20261207-r2")).containsEntry("status", "PUBLISHED"),
            () -> assertThat(tables.resultChecksumOf("weekly-20261207-r2")).isEqualTo(tables.resultChecksumOf("weekly-20261207-r1")),
            () -> assertThat(commands.nextParameters(week).revision()).isEqualTo(3)
        );
    }

    @DisplayName("실패한 뒤 기간만 넘겨 다시 실행하면, 새 번호가 아니라 같은 revision · 같은 JobInstance 로 이어 간다.")
    @Test
    void resumesSameRevisionAfterFailure() throws Exception {
        // arrange
        LocalDate week = LocalDate.parse("2026-12-14");
        tables.confirmLabFixture(week);
        failBeforeProduct(9L);
        assertThrows(IllegalStateException.class, () -> commands.run(week));
        Mockito.reset(writer);

        // act
        WeeklyRankingParameters next = commands.nextParameters(week);
        JobExecution resumed = commands.run(week);

        // assert
        JobInstance instance = jobExplorer.getLastJobInstance(WeeklyRankingJobConfig.JOB_NAME);
        assertAll(
            () -> assertThat(next.revision()).isEqualTo(1),
            () -> assertThat(resumed.getJobInstance().getInstanceId()).isEqualTo(instance.getInstanceId()),
            () -> assertThat(jobExplorer.getJobExecutions(instance)).hasSize(2),
            () -> assertThat(tables.manifestOf("weekly-20261214-r1")).containsEntry("status", "PUBLISHED")
        );
    }

    @DisplayName("막힌 revision 을 포기하면 다음 실행은 새 revision 으로 입력을 다시 고정하고, 공개본은 포기할 수 없다.")
    @Test
    void abandonsStuckRevision() throws Exception {
        // arrange
        LocalDate week = LocalDate.parse("2026-12-21");
        tables.confirmLabFixture(week);
        failBeforeProduct(9L);
        assertThrows(IllegalStateException.class, () -> commands.run(week));
        Mockito.reset(writer);

        // act
        commands.abandon(week, 1);
        commands.run(week);

        // assert
        assertAll(
            () -> assertThat(tables.manifestOf("weekly-20261221-r1")).containsEntry("status", "ABANDONED"),
            () -> assertThat(tables.manifestOf("weekly-20261221-r2")).containsEntry("status", "PUBLISHED"),
            () -> assertThat(tables.inputsOf("weekly-20261221-r2")).hasSize(4),
            () -> assertThrows(IllegalStateException.class, () -> commands.abandon(week, 2))
        );
    }

    @DisplayName("늦은 구매 확인은 공개 snapshot 입력에 없는 품목 수를 세고 결과를 바꾸지 않으며, 공개본이 없으면 -1 이다.")
    @Test
    void countsLateArrivalsAgainstPublishedSnapshot() throws Exception {
        // arrange
        LocalDate week = LocalDate.parse("2026-12-28");
        tables.confirmLabFixture(week);
        int beforePublish = commands.countLateArrivals(week);
        commands.run(week);
        int noLate = commands.countLateArrivals(week);
        // 원래 주에 확정됐지만 snapshot 고정 뒤 저장된 주문(두 품목)과 다음 주 주문
        tables.confirm(46L, week.plusDays(4) + " 01:00:00", Map.of(7L, 1, 9L, 2));
        tables.confirm(47L, week.plusDays(7) + " 00:00:00", Map.of(7L, 1));

        // act
        int late = commands.countLateArrivals(week);

        // assert
        assertAll(
            () -> assertThat(beforePublish).isEqualTo(-1),
            () -> assertThat(noLate).isZero(),
            () -> assertThat(late).isEqualTo(2),
            () -> assertThat(tables.manifestOf("weekly-20261228-r1")).containsEntry("status", "PUBLISHED"),
            () -> assertThat(tables.inputsOf("weekly-20261228-r1")).hasSize(4)
        );
    }

    private void failBeforeProduct(long productId) throws Exception {
        doThrow(new IllegalStateException("before product " + productId))
            .when(writer).write(argThat((Chunk<? extends ProductKey> chunk) -> chunk != null
                && chunk.getItems().stream().anyMatch(key -> key.productId() == productId)));
    }
}
