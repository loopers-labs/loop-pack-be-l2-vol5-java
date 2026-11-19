package com.loopers.batch.job.ranking;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 주간 랭킹 실행 진입점. 예: --job.name=weeklyRanking --spring.batch.job.enabled=false --weekly-ranking.command=run --periodStart=2026-08-17
 * command 는 run(실행 · 재시작) · check(늦은 구매 확인) · abandon(--revision 필요). 실패하면 예외로 끝나 종료 코드가 0 이 아니다
 */
@ConditionalOnProperty(name = "weekly-ranking.command")
@RequiredArgsConstructor
@Component
public class WeeklyRankingCommandRunner implements ApplicationRunner {

    private final WeeklyRankingCommands commands;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Boot 의 기본 runner 는 revision 없이 job 을 실행하므로 함께 켜지면 안 된다
        if (environment.getProperty("spring.batch.job.enabled", Boolean.class, true)) {
            throw new IllegalStateException("--spring.batch.job.enabled=false 와 함께 실행해야 함");
        }
        LocalDate periodStart = LocalDate.parse(required(args, "periodStart"));
        String command = environment.getRequiredProperty("weekly-ranking.command");
        switch (command) {
            case "run" -> commands.run(periodStart);
            case "check" -> commands.countLateArrivals(periodStart);
            case "abandon" -> commands.abandon(periodStart, Integer.parseInt(required(args, "revision")));
            default -> throw new IllegalArgumentException("모르는 command: " + command);
        }
    }

    private static String required(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("--" + name + " 값 하나가 필요함");
        }
        return values.get(0);
    }
}
