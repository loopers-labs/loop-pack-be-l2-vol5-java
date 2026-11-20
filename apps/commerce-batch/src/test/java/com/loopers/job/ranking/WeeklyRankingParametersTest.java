package com.loopers.job.ranking;

import com.loopers.job.ranking.support.WeeklyRankingJobTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyRankingParametersTest extends WeeklyRankingJobTestSupport {

    @DisplayName("월요일이 아닌 periodStart는 JobExecution을 만들기 전에 거부한다")
    @Test
    void rejectsNonMondayPeriod() {
        assertThatThrownBy(() -> jobLauncher.run(job, params("2026-08-18", "weekly-20260818-r1")))
                .isInstanceOf(JobParametersInvalidException.class);
    }

    @DisplayName("snapshotId의 날짜가 periodStart와 다르면 거부한다")
    @Test
    void rejectsSnapshotOfAnotherPeriod() {
        assertThatThrownBy(() -> jobLauncher.run(job, params(WEEK, "weekly-20260824-r1")))
                .isInstanceOf(JobParametersInvalidException.class);
    }

    @DisplayName("run.id 같은 다른 식별 parameter를 섞으면 새 JobInstance가 되므로 거부한다")
    @Test
    void rejectsExtraIdentifyingParameter() {
        var withRunId = new JobParametersBuilder(params(WEEK, snapshot(1)))
                .addLong("run.id", 1L, true).toJobParameters();

        assertThatThrownBy(() -> jobLauncher.run(job, withRunId))
                .isInstanceOf(JobParametersInvalidException.class);
    }

    @Nested
    class SamePeriodAlreadyRunning {
        private JobExecution blocked;
        private JobExecution otherPeriod;
        private JobExecution restart;

        @BeforeEach
        void blockSamePeriodThenRestartAfterPriorExecutionEnds() throws Exception {
            database.insertWeek20260817Fixture();
            JobExecution running = jobRepository.createJobExecution(job.getName(), params(WEEK, snapshot(1)));
            blocked = launch(snapshot(2));
            otherPeriod = jobLauncher.run(job, params("2026-08-24", "weekly-20260824-r1"));

            running.setStatus(BatchStatus.FAILED);
            running.setExitStatus(ExitStatus.FAILED);
            running.setEndTime(LocalDateTime.now());
            jobRepository.update(running);
            restart = launch(snapshot(2));
        }

        @Test
        @DisplayName("같은 기간의 다른 revision은 실패한다")
        void samePeriodIsBlocked() {
            assertThat(blocked.getStatus()).isEqualTo(BatchStatus.FAILED);
        }

        @Test
        @DisplayName("차단된 작업은 어떤 Step도 시작하지 않는다")
        void blockedExecutionRunsNoStep() {
            assertThat(blocked.getStepExecutions()).isEmpty();
        }

        @Test
        @DisplayName("차단 이유는 같은 기간의 실행이 진행 중이기 때문이다")
        void blockedExecutionReportsRunningPeriod() {
            assertThat(blocked.getAllFailureExceptions()).anyMatch(e -> e.getMessage().contains("already running"));
        }

        @Test
        @DisplayName("다른 기간의 작업은 정상 완료된다")
        void otherPeriodCompletes() {
            assertThat(otherPeriod.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("차단된 작업의 재시작은 같은 JobInstance다")
        void restartUsesSameInstance() {
            assertThat(restart.getJobInstance().getInstanceId()).isEqualTo(blocked.getJobInstance().getInstanceId());
        }

        @Test
        @DisplayName("이전 실행이 끝나면 차단된 작업을 재시작할 수 있다")
        void restartCompletesAfterPreviousExecutionEnds() {
            assertThat(restart.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }
    }
}
