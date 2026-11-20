package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import com.loopers.batch.job.ranking.step.PublishRankingTasklet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.loopers.job.ranking.PurchaseActivityFixture.purchase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * 공개 단계: 계산 실패·공개 TX rollback에서 이전 공개본 유지, 완성 조건, 오래된 revision 보호, 같은 기간의 직렬 실행,
 * 공개 commit 뒤 Batch 기록 실패를 확인한다. 실패 스위치는 job parameter가 아니라 spy다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "commerce.ranking.weekly.chunk-size=2"
})
class WeeklyRankingPublishTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobRepository jobRepository;

    @MockitoSpyBean
    private WeeklyRankingStore store;

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private PurchaseActivityFixture activities;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        activities = new PurchaseActivityFixture(jdbc);
    }

    @DisplayName("새 revision의 계산이 실패하면 이전 공개본이 그대로이고, 재시작이 끝나면 새 revision으로 바뀐다.")
    @Test
    void keepsPreviousPublicationWhenScoringFails() throws Exception {
        activities.insert(purchase(601, 1, 3, "2027-02-02T01:00:00"));
        assertThat(run("2027-02-01", "weekly-20270201-r1").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        activities.insert(purchase(602, 2, 4, "2027-02-03T01:00:00"));  // r1 공개 뒤 도착한 같은 주 구매

        failOnce(() -> doAnswer(invocation -> {
            List<ProductScore> scores = invocation.getArgument(1);
            if (scores.stream().anyMatch(score -> score.productId() == 2L) && FAILED.compareAndSet(false, true)) {
                throw new IllegalStateException("r2 상품 2 저장 직전 실패");
            }
            return invocation.callRealMethod();
        }).when(store).insertScores(anyString(), anyList()));

        JobExecution failed = run("2027-02-01", "weekly-20270201-r2");

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(publishedSnapshot("2027-02-01")).isEqualTo("weekly-20270201-r1");
        assertThat(entries("weekly-20270201-r1")).containsExactly("1:3:1");
        assertThat(entries("weekly-20270201-r2")).isEmpty();
        assertThat(status("weekly-20270201-r2")).isEqualTo(WeeklyRankingStore.STATUS_FROZEN);

        JobExecution restarted = run("2027-02-01", "weekly-20270201-r2");

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getInstanceId()).isEqualTo(failed.getJobInstance().getInstanceId());
        assertThat(publishedSnapshot("2027-02-01")).isEqualTo("weekly-20270201-r2");
        assertThat(entries("weekly-20270201-r2")).containsExactly("2:4:1", "1:3:2");
        assertThat(entries("weekly-20270201-r1")).containsExactly("1:3:1");  // 이전 결과는 지우지 않는다
    }

    @DisplayName("공개 TX가 pointer를 바꾸기 전에 실패하면 완성 랭킹·완성 표시까지 rollback되고 이전 공개본이 그대로다.")
    @Test
    void rollsBackWholePublicationOnFailure() throws Exception {
        activities.insert(purchase(611, 1, 3, "2027-02-16T01:00:00"));
        assertThat(run("2027-02-15", "weekly-20270215-r1").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        activities.insert(purchase(612, 2, 4, "2027-02-17T01:00:00"));

        failOnce(() -> doAnswer(invocation -> {
            if (FAILED.compareAndSet(false, true)) {
                throw new IllegalStateException("pointer 교체 직전 실패");
            }
            return invocation.callRealMethod();
        }).when(store).replacePublication(any(), anyInt()));

        JobExecution failed = run("2027-02-15", "weekly-20270215-r2");

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(reloadStep(failed, WeeklyRankingJobConfig.PUBLISH_STEP).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(publishedSnapshot("2027-02-15")).isEqualTo("weekly-20270215-r1");
        assertThat(entries("weekly-20270215-r2")).isEmpty();
        assertThat(status("weekly-20270215-r2")).isEqualTo(WeeklyRankingStore.STATUS_FROZEN);

        JobExecution restarted = run("2027-02-15", "weekly-20270215-r2");

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getStepExecutions()).extracting(StepExecution::getStepName)
            .containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(publishedSnapshot("2027-02-15")).isEqualTo("weekly-20270215-r2");
        assertThat(entries("weekly-20270215-r2")).containsExactly("2:4:1", "1:3:2");
    }

    @DisplayName("stage 점수 합이 입력 수량 합과 다르면 공개하지 않고, 그 snapshot을 ABANDONED로 표시하면 다음 revision으로 다시 계산할 수 있다.")
    @Test
    void doesNotPublishIncompleteScores() throws Exception {
        activities.insert(
            purchase(621, 1, 3, "2027-03-02T01:00:00"),
            purchase(622, 2, 4, "2027-03-03T01:00:00"));
        // 잘못 계산된 stage를 흉내 낸다: 상품 2의 점수를 1 크게 저장한다.
        doAnswer(invocation -> {
            invocation.callRealMethod();
            String snapshotId = invocation.getArgument(0);
            jdbc.update("UPDATE ranking_score_stage SET score = score + 1 WHERE snapshot_id = ? AND product_id = 2", snapshotId);
            return null;
        }).when(store).insertScores(anyString(), anyList());

        JobExecution execution = run("2027-03-01", "weekly-20270301-r1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        StepExecution publish = reloadStep(execution, WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(publish.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(publish.getExitStatus().getExitDescription()).contains("완성 조건");
        assertThat(publishedSnapshot("2027-03-01")).isNull();
        assertThat(entries("weekly-20270301-r1")).isEmpty();
        assertThat(status("weekly-20270301-r1")).isEqualTo(WeeklyRankingStore.STATUS_FROZEN);

        // 복구: 계산 버그를 고치고(spy 해제), 틀린 r1을 지우지 않고 ABANDONED로 표시한 뒤 다음 revision으로 다시 계산한다.
        reset(store);
        assertThat(run("2027-03-01", "weekly-20270301-r2").getStatus()).isEqualTo(BatchStatus.FAILED);  // r1이 FROZEN이라 막힘
        jdbc.update("UPDATE ranking_snapshot SET status = 'ABANDONED' WHERE snapshot_id = ? AND status = 'FROZEN'",
            "weekly-20270301-r1");

        assertThat(run("2027-03-01", "weekly-20270301-r3").getStatus()).isEqualTo(BatchStatus.FAILED);  // 건너뛰기는 여전히 금지
        JobExecution next = run("2027-03-01", "weekly-20270301-r2");  // 앞에서 막힌 r2 instance의 재시작

        assertThat(next.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(publishedSnapshot("2027-03-01")).isEqualTo("weekly-20270301-r2");
        assertThat(entries("weekly-20270301-r2")).containsExactly("2:4:1", "1:3:2");
        // 포기한 r1을 재시작해도 공개하지 않는다.
        assertThat(run("2027-03-01", "weekly-20270301-r1").getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(publishedSnapshot("2027-03-01")).isEqualTo("weekly-20270301-r2");
    }

    @DisplayName("이미 더 새 revision이 공개돼 있으면 오래된 revision은 완성만 하고 pointer를 되돌리지 않는다.")
    @Test
    void olderRevisionDoesNotOverwriteNewerPublication() throws Exception {
        activities.insert(purchase(631, 1, 3, "2027-03-16T01:00:00"));
        // 다른 실행이 r2를 이미 공개한 상태
        jdbc.update("""
            INSERT INTO ranking_publication (period_start, snapshot_id, revision, published_at)
            VALUES ('2027-03-15', 'weekly-20270315-r2', 2, UTC_TIMESTAMP(6))
            """);

        JobExecution execution = run("2027-03-15", "weekly-20270315-r1");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(PublishRankingTasklet.NOT_PUBLISHED_OLDER_REVISION);
        assertThat(publishedSnapshot("2027-03-15")).isEqualTo("weekly-20270315-r2");
        assertThat(status("weekly-20270315-r1")).isEqualTo(WeeklyRankingStore.STATUS_COMPLETED);
        assertThat(entries("weekly-20270315-r1")).containsExactly("1:3:1");
    }

    @DisplayName("같은 기간은 이전 revision이 완성돼야 다음 revision을 시작한다.")
    @Test
    void runsRevisionsOfSamePeriodOneAtATime() throws Exception {
        activities.insert(
            purchase(641, 1, 3, "2027-03-30T01:00:00"),
            purchase(642, 2, 4, "2027-03-31T01:00:00"));
        failOnce(() -> doAnswer(invocation -> {
            if (FAILED.compareAndSet(false, true)) {
                throw new IllegalStateException("r1 계산 실패");
            }
            return invocation.callRealMethod();
        }).when(store).insertScores(anyString(), anyList()));

        assertThat(run("2027-03-29", "weekly-20270329-r1").getStatus()).isEqualTo(BatchStatus.FAILED);

        JobExecution blocked = run("2027-03-29", "weekly-20270329-r2");
        assertThat(blocked.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(reloadStep(blocked, WeeklyRankingJobConfig.FREEZE_STEP).getExitStatus().getExitDescription())
            .contains("이전 revision이 아직 완성되지 않았습니다");
        assertThat(store.findSnapshot("weekly-20270329-r2")).isEmpty();

        assertThat(run("2027-03-29", "weekly-20270329-r1").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run("2027-03-29", "weekly-20270329-r2").getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(publishedSnapshot("2027-03-29")).isEqualTo("weekly-20270329-r2");
    }

    @DisplayName("공개 TX commit 뒤 Batch 기록만 실패하면 공개본은 그대로이고, 사실을 확인해 실행을 FAILED로 표시한 뒤 재시작하면 같은 결과로 수렴한다.")
    @Test
    void convergesAfterMetadataFailureFollowingPublication() throws Exception {
        activities.insert(purchase(651, 1, 3, "2027-04-13T01:00:00"));
        JobParameters params = params("2027-04-12", "weekly-20270412-r1");
        // 공개 Step이 끝나고(COMPLETED로 바뀐 뒤) JobRepository에 최종 상태를 저장하는 호출을 한 번 실패시킨다.
        FAIL_PUBLISH_COMPLETION.set(true);

        JobExecution first = jobLauncher.run(job, params);

        // Batch 기록: 끝났는지 알 수 없음. 공개 Step은 DB에 STARTED로 남는다.
        assertThat(jobExplorer.getJobExecution(first.getId()).getStatus()).isEqualTo(BatchStatus.UNKNOWN);
        assertThat(reloadStep(first, WeeklyRankingJobConfig.PUBLISH_STEP).getStatus()).isEqualTo(BatchStatus.STARTED);
        // 실제 업무 상태: 공개는 commit됐다.
        String resultChecksum = store.findSnapshot("weekly-20270412-r1").orElseThrow().resultChecksum();
        assertThat(publishedSnapshot("2027-04-12")).isEqualTo("weekly-20270412-r1");
        assertThat(status("weekly-20270412-r1")).isEqualTo(WeeklyRankingStore.STATUS_COMPLETED);
        assertThat(entries("weekly-20270412-r1")).containsExactly("1:3:1");
        assertThat(store.resultChecksum("weekly-20270412-r1")).isEqualTo(resultChecksum);
        LocalDateTime completedAt = completedAt("weekly-20270412-r1");

        // 같은 parameter의 재실행은 Spring Batch가 거부한다(끝났는지 모르는 실행이 남아 있음).
        assertThatThrownBy(() -> jobLauncher.run(job, params)).isInstanceOf(JobExecutionAlreadyRunningException.class);

        // 운영 절차: 위 사실을 확인한 뒤, 멈춘 실행을 지우지 않고 FAILED로 표시한다.
        markFailed(first);

        JobExecution restarted = jobLauncher.run(job, params);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(restarted.getStepExecutions()).extracting(StepExecution::getStepName)
            .containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(publishedSnapshot("2027-04-12")).isEqualTo("weekly-20270412-r1");
        assertThat(entries("weekly-20270412-r1")).containsExactly("1:3:1");
        assertThat(store.findSnapshot("weekly-20270412-r1").orElseThrow().resultChecksum()).isEqualTo(resultChecksum);
        assertThat(completedAt("weekly-20270412-r1")).isEqualTo(completedAt);  // 완성 랭킹을 다시 만들지 않았다
    }

    /** 끝났는지 모르는 실행(공개 Step STARTED, Job UNKNOWN)을 FAILED로 표시한다. 기록을 지우지 않는다. */
    private void markFailed(JobExecution execution) {
        LocalDateTime now = LocalDateTime.now();
        StepExecution publish = reloadStep(execution, WeeklyRankingJobConfig.PUBLISH_STEP);
        publish.setStatus(BatchStatus.FAILED);
        publish.setExitStatus(ExitStatus.FAILED.addExitDescription("공개 commit 확인 뒤 운영자가 FAILED로 표시"));
        publish.setEndTime(now);
        jobRepository.update(publish);
        JobExecution job = jobExplorer.getJobExecution(execution.getId());
        job.setStatus(BatchStatus.FAILED);
        job.setExitStatus(ExitStatus.FAILED);
        job.setEndTime(now);
        jobRepository.update(job);
    }

    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final AtomicBoolean FAIL_PUBLISH_COMPLETION = new AtomicBoolean();

    /**
     * JobRepository 빈은 인터페이스 프록시라 Mockito spy로 실제 메서드를 부를 수 없다.
     * 테스트에서만 감싸서, 스위치가 켜졌을 때 공개 Step의 최종 상태(COMPLETED) 저장을 한 번 실패시킨다.
     */
    @TestConfiguration
    static class FailingJobRepositoryConfig {
        @Bean
        static BeanPostProcessor failPublishCompletionOnce() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof JobRepository repository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(JobRepository.class.getClassLoader(), new Class<?>[] {JobRepository.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("update") && args != null && args[0] instanceof StepExecution step
                                && step.getStepName().equals(WeeklyRankingJobConfig.PUBLISH_STEP)
                                && step.getStatus() == BatchStatus.COMPLETED
                                && FAIL_PUBLISH_COMPLETION.compareAndSet(true, false)) {
                                throw new IllegalStateException("공개 Step 최종 상태 저장 실패");
                            }
                            try {
                                return method.invoke(repository, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
                }
            };
        }
    }

    /** 테스트마다 한 번만 실패하도록 스위치를 초기화하고 spy를 설정한다. */
    private static void failOnce(Runnable stubbing) {
        FAILED.set(false);
        stubbing.run();
    }

    private JobExecution run(String periodStart, String snapshotId) throws Exception {
        return jobLauncher.run(job, params(periodStart, snapshotId));
    }

    private static JobParameters params(String periodStart, String snapshotId) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart, true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();
    }

    private LocalDateTime completedAt(String snapshotId) {
        return jdbc.queryForObject("SELECT completed_at FROM ranking_snapshot WHERE snapshot_id = ?",
            LocalDateTime.class, snapshotId);
    }

    private StepExecution reloadStep(JobExecution execution, String stepName) {
        StepExecution inMemory = execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(stepName))
            .findFirst()
            .orElseThrow();
        return jobExplorer.getStepExecution(execution.getId(), inMemory.getId());
    }

    private String publishedSnapshot(String periodStart) {
        return jdbc.query("SELECT snapshot_id FROM ranking_publication WHERE period_start = ?",
            (rs, n) -> rs.getString("snapshot_id"), periodStart).stream().findFirst().orElse(null);
    }

    private String status(String snapshotId) {
        return store.findSnapshot(snapshotId).map(WeeklyRankingStore.SnapshotManifest::status).orElse(null);
    }

    private List<String> entries(String snapshotId) {
        return jdbc.query(
            "SELECT product_id, score, ranking FROM ranking_entry WHERE snapshot_id = ? ORDER BY ranking",
            (rs, n) -> rs.getLong("product_id") + ":" + rs.getLong("score") + ":" + rs.getInt("ranking"),
            snapshotId);
    }
}
