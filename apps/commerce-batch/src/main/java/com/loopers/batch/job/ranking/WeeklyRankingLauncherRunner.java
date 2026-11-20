package com.loopers.batch.job.ranking;

import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

// 런처 진입점(공개 API 없음): --job.name=weeklyRanking --spring.batch.job.enabled=false --ranking.period=YYYY-MM-DD [--ranking.size=K]
// Boot 기본 job 실행기가 parameter 없이 같은 job을 함께 돌리지 않도록 기본 실행기가 꺼져 있어야 시작한다.
@Slf4j
public class WeeklyRankingLauncherRunner implements ApplicationRunner, ExitCodeGenerator {
    private final WeeklyRankingLauncher weeklyRankingLauncher;
    private int exitCode;

    public WeeklyRankingLauncherRunner(WeeklyRankingLauncher weeklyRankingLauncher, boolean defaultRunnerEnabled) {
        if (defaultRunnerEnabled) {
            throw new IllegalStateException("런처는 --spring.batch.job.enabled=false와 함께 실행해야 합니다.");
        }
        this.weeklyRankingLauncher = weeklyRankingLauncher;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        LocalDate periodStart = LocalDate.parse(args.getOptionValues("ranking.period").getFirst());
        Integer rankingSize = args.containsOption("ranking.size")
            ? Integer.valueOf(args.getOptionValues("ranking.size").getFirst()) : null;
        JobExecution execution = weeklyRankingLauncher.launch(periodStart, rankingSize);
        log.info("주간 랭킹 런처 실행 끝. periodStart={}, snapshotId={}, status={}, exitCode={}", periodStart,
            execution.getJobParameters().getString(WeeklyRankingParameters.SNAPSHOT_ID), execution.getStatus(),
            execution.getExitStatus().getExitCode());
        exitCode = execution.getStatus() == BatchStatus.COMPLETED ? 0 : 1;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
