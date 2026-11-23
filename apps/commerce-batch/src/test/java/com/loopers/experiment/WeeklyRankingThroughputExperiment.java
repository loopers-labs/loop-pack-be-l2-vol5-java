package com.loopers.experiment;

import com.loopers.CommerceBatchApplication;
import com.loopers.batch.job.ranking.RankingTables;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.command.WeeklyRankingCommands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9주차 주간 랭킹 job 처리량 실험. 같은 원본을 chunk 크기마다 새 revision 으로 다시 계산해 step 별 소요 시간을 잰다.
 * chunk 크기는 context 시작 때 정해지므로 실행마다 context 를 새로 띄운다. 크기 순서 영향을 줄이려고 정순 · 역순으로 두 번 돈다.
 * 로컬 Testcontainers MySQL 값이라 운영을 대표하지 않음. 결과는 build/experiment/weekly-ranking-throughput.md 에 남김.
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-batch:test --tests '*WeeklyRankingThroughputExperiment'
 */
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class WeeklyRankingThroughputExperiment {

    private static final Logger log = LoggerFactory.getLogger(WeeklyRankingThroughputExperiment.class);
    private static final Path REPORT = Path.of("build", "experiment", "weekly-ranking-throughput.md");
    private static final LocalDate WEEK = LocalDate.parse("2027-03-01");
    private static final int ORDERS_PER_WEEK = 250_000;
    private static final int PRODUCTS = 50_000;
    private static final int PAGE_SIZE = 5000;
    private static final String OR_KEY = "(a.order_id > ? or (a.order_id = ? and a.product_id > ?))";
    private static final List<Integer> CHUNK_SIZES = List.of(100, 500, 1000, 5000);
    private static final List<String> STEPS = List.of(
        WeeklyRankingJobConfig.FREEZE_STEP, WeeklyRankingJobConfig.SCORE_STEP,
        WeeklyRankingJobConfig.COMPLETE_STEP, WeeklyRankingJobConfig.PUBLISH_STEP);

    private record Run(String label, int chunkSize, Map<String, Duration> steps, Duration total, long scoreCommits, String checksum) {
    }

    @DisplayName("주문 25만 · 활동 50만 · 상품 5만에서 chunk 크기별 step 소요 시간")
    @Test
    void measuresStepDurationsByChunkSize() throws Exception {
        // arrange
        Map<String, Object> source;
        long seedMillis;
        try (ConfigurableApplicationContext context = start(CHUNK_SIZES.get(0))) {
            context.getBean(RankingTables.class).truncate();
            long started = System.nanoTime();
            seed(context.getBean(JdbcTemplate.class));
            seedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            source = context.getBean(JdbcTemplate.class).queryForMap("""
                select count(*) as activities, count(distinct a.product_id) as products
                from processed_order_confirmation c join product_purchase_activity a on a.order_id = c.order_id
                where c.confirmed_at >= ? and c.confirmed_at < ?""", WEEK.atStartOfDay(), WEEK.plusDays(7).atStartOfDay());
            log.info("seed 완료. {}ms source={}", seedMillis, source);
            // 예열: JIT · 커넥션 · 버퍼 풀. 결과에서 뺀다
            measure(context, "warmup", CHUNK_SIZES.get(0));
        }

        // act
        List<Integer> order = new ArrayList<>(CHUNK_SIZES);
        order.addAll(CHUNK_SIZES.reversed());
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            int chunkSize = order.get(i);
            try (ConfigurableApplicationContext context = start(chunkSize)) {
                runs.add(measure(context, i < CHUNK_SIZES.size() ? "정순" : "역순", chunkSize));
            }
        }

        // assert
        writeReport(source, seedMillis, runs);
        assertThat(runs).extracting(Run::checksum).containsOnly(runs.get(0).checksum());
    }

    @DisplayName("입력 고정을 keyset 페이지로 읽을 때의 실행 계획과 한 TX 안 전체 페이지 소요 시간")
    @Test
    void explainsKeysetPaging() throws Exception {
        Path report = Path.of("build", "experiment", "weekly-ranking-keyset.md");
        try (ConfigurableApplicationContext context = start(CHUNK_SIZES.get(0))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            context.getBean(RankingTables.class).truncate();
            seed(jdbc);
            // seed 직후 통계는 행 수를 모른다. 운영 테이블처럼 통계가 있는 상태에서 계획을 본다
            jdbc.execute("analyze table processed_order_confirmation, product_purchase_activity");
            Object[] range = {WEEK.atStartOfDay(), WEEK.plusDays(7).atStartOfDay()};
            // 대상 주 입력의 가운데쯤 키. 첫 페이지만 보면 앞쪽이라 계획이 좋게 보일 수 있다
            Map<String, Object> middle = jdbc.queryForMap("""
                select a.order_id, a.product_id from processed_order_confirmation c
                join product_purchase_activity a on a.order_id = c.order_id
                where c.confirmed_at >= ? and c.confirmed_at < ?
                order by a.order_id, a.product_id limit 1 offset 250000""", range);

            StringBuilder out = new StringBuilder("# keyset 페이지 읽기 확인\n\n");
            Map<String, String> forms = new LinkedHashMap<>();
            forms.put("행 생성자", "(a.order_id, a.product_id) > (?, ?)");
            forms.put("OR 풀어 쓰기", OR_KEY);
            forms.put("OR + 인덱스 순서 강제", OR_KEY);
            for (Map.Entry<String, String> form : forms.entrySet()) {
                Object[] args = keysetArgs(form.getKey(), middle, range);
                String sql = keysetSql(form.getValue(), PAGE_SIZE, form.getKey().endsWith("강제"));
                String plan = String.join("\n", jdbc.queryForList("explain analyze " + sql, String.class, args));
                out.append("## ").append(form.getKey()).append(" · 가운데 페이지 EXPLAIN ANALYZE\n\n```\n").append(plan).append("\n```\n\n");
            }

            // 현재 방식(한 번에 읽기)과 keyset 전체 페이지를 같은 조건(한 TX, 읽기만)으로 잰다
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            long single = timeMillis(() -> tx.execute(status -> jdbc.query("""
                select a.order_id, a.product_id, a.quantity, c.confirmed_at
                from processed_order_confirmation c join product_purchase_activity a on a.order_id = c.order_id
                where c.confirmed_at >= ? and c.confirmed_at < ? order by a.order_id, a.product_id""",
                (rs, rowNum) -> rowNum, range).size()));
            out.append("| 방식 | 페이지 크기 | 페이지 수 | 행 수 | 소요 ms |\n|---|---|---|---|---|\n");
            out.append("| 한 번에 읽기 (현재) | - | 1 | 500,000 | %,d |%n".formatted(single));
            for (boolean forced : List.of(false, true))
            for (int pageSize : List.of(1000, PAGE_SIZE, 20_000)) {
                int[] pages = {0};
                long[] rows = {0};
                long millis = timeMillis(() -> tx.execute(status -> {
                    long lastOrder = 0;
                    long lastProduct = 0;
                    while (true) {
                        List<long[]> page = jdbc.query(keysetSql(OR_KEY, pageSize, forced),
                            (rs, rowNum) -> new long[] {rs.getLong("order_id"), rs.getLong("product_id")},
                            lastOrder, lastOrder, lastProduct, range[0], range[1]);
                        if (page.isEmpty()) {
                            return null;
                        }
                        pages[0]++;
                        rows[0] += page.size();
                        lastOrder = page.getLast()[0];
                        lastProduct = page.getLast()[1];
                    }
                }));
                out.append("| keyset (OR%s) | %,d | %,d | %,d | %,d |%n".formatted(forced ? " + 강제" : "", pageSize, pages[0], rows[0], millis));
            }
            Files.createDirectories(report.getParent());
            Files.writeString(report, out);
            log.info("keyset 확인\n{}", out);
        }
    }

    /** forced: activity 를 (order_id, product_id) 인덱스 순서로 먼저 읽게 해 limit 에서 멈추게 한다 */
    private static String keysetSql(String keyCondition, int pageSize, boolean forced) {
        return """
            select %s a.order_id, a.product_id, a.quantity, c.confirmed_at
            from product_purchase_activity a %s
            join processed_order_confirmation c on c.order_id = a.order_id
            where %s and c.confirmed_at >= ? and c.confirmed_at < ?
            order by a.order_id, a.product_id limit %d""".formatted(
            forced ? "straight_join" : "", forced ? "force index (uk_product_purchase_activity_order_product)" : "",
            keyCondition, pageSize);
    }

    private static Object[] keysetArgs(String form, Map<String, Object> key, Object[] range) {
        Object order = key.get("order_id");
        Object product = key.get("product_id");
        return form.equals("행 생성자")
            ? new Object[] {order, product, range[0], range[1]}
            : new Object[] {order, order, product, range[0], range[1]};
    }

    private static long timeMillis(Runnable work) {
        long started = System.nanoTime();
        work.run();
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private ConfigurableApplicationContext start(int chunkSize) {
        // 기본 프로퍼티로 주면 application.yml 의 job 이름이 덮어쓰므로 실행 인자로 준다
        return new SpringApplicationBuilder(CommerceBatchApplication.class).run(
            "--spring.batch.job.enabled=false",
            "--spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
            "--weekly-ranking.chunk-size=" + chunkSize);
    }

    /**
     * 대상 주와 그 전 주에 주문을 같은 수만큼 둔다. confirmed_at 인덱스가 없어 입력 고정이 다른 주 행까지 훑는 비용을 보려는 것.
     * 주문마다 서로 다른 상품 두 개. 첫 상품은 작은 id 쪽으로 몰리게(제곱 분포), 둘째는 고르게 둔다. CRC32 로 결정적으로 만든다
     */
    private void seed(JdbcTemplate jdbc) {
        jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set session cte_max_recursion_depth = " + (ORDERS_PER_WEEK * 2 + 1));
                statement.execute("""
                    insert into processed_order_confirmation (event_id, order_id, confirmed_at, created_at, updated_at)
                    with recursive seq(n) as (select 1 union all select n + 1 from seq where n < %d)
                    select concat('exp-', n), n,
                           timestamp('%s') + interval if(n <= %d, 0, -7) day + interval ((n %% %d) * 2) second,
                           now(6), now(6)
                    from seq""".formatted(ORDERS_PER_WEEK * 2, WEEK, ORDERS_PER_WEEK, ORDERS_PER_WEEK));
                statement.execute("""
                    insert into product_purchase_activity (order_id, product_id, quantity, created_at, updated_at)
                    with picked as (
                        select order_id,
                               1 + floor(%d * pow((crc32(concat(order_id, 'a')) %% 1000000) / 1000000, 2)) as first_product,
                               crc32(concat(order_id, 'b')) %% %d as offset
                        from processed_order_confirmation)
                    select order_id, first_product, 1 + crc32(concat(order_id, 'q1')) %% 5, now(6), now(6) from picked
                    union all
                    select order_id, 1 + mod(first_product + offset, %d), 1 + crc32(concat(order_id, 'q2')) %% 5, now(6), now(6) from picked
                    """.formatted(PRODUCTS, PRODUCTS - 1, PRODUCTS));
            }
            return null;
        });
    }

    private Run measure(ConfigurableApplicationContext context, String label, int chunkSize) throws Exception {
        JobExecution execution = context.getBean(WeeklyRankingCommands.class).run(WEEK);
        String snapshotId = WeeklyRankingParameters.from(execution.getJobParameters()).snapshotId();
        Map<String, Duration> steps = new LinkedHashMap<>();
        long scoreCommits = 0;
        for (StepExecution step : execution.getStepExecutions()) {
            steps.put(step.getStepName(), Duration.between(step.getStartTime(), step.getEndTime()));
            if (step.getStepName().equals(WeeklyRankingJobConfig.SCORE_STEP)) {
                scoreCommits = step.getCommitCount();
            }
        }
        String checksum = context.getBean(RankingTables.class).resultChecksumOf(snapshotId);
        Run run = new Run(label, chunkSize, steps, Duration.between(execution.getStartTime(), execution.getEndTime()), scoreCommits, checksum);
        log.info("측정. {} chunk={} snapshotId={} steps={} total={}", label, chunkSize, snapshotId, steps, run.total());

        // 다음 실행이 커진 입력 · 점수 테이블을 쓰지 않게 비운다. 측정 밖
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        jdbc.execute("truncate table weekly_ranking_input");
        jdbc.execute("truncate table weekly_ranking_score");
        return run;
    }

    private void writeReport(Map<String, Object> source, long seedMillis, List<Run> runs) throws IOException {
        long products = ((Number) source.get("products")).longValue();
        Function<Duration, String> seconds = duration -> "%.2f".formatted(duration.toMillis() / 1000.0);
        StringBuilder report = new StringBuilder("""
            # 주간 랭킹 job 처리량 실험

            - 원본: 대상 주 · 전 주 주문 각 %,d (활동 각 %,d), 상품 %,d 중 대상 주에 나온 상품 %,d
            - 대상 주 활동(입력): %,d 행, seed %,d ms
            - 시간 단위: 초. 상품/초 = 점수 계산 step 의 상품 수 ÷ 소요 시간

            | 순서 | chunk | 입력 고정 | 점수 계산 | 계산 완료 | 공개 | job 전체 | 점수 commit 수 | 상품/초 |
            |---|---|---|---|---|---|---|---|---|
            """.formatted(ORDERS_PER_WEEK, ORDERS_PER_WEEK * 2, PRODUCTS, products,
            ((Number) source.get("activities")).longValue(), seedMillis));
        for (Run run : runs) {
            Duration score = run.steps().get(WeeklyRankingJobConfig.SCORE_STEP);
            report.append("| %s | %d | %s | %s | %s | %s | %s | %d | %,.0f |%n".formatted(
                run.label(), run.chunkSize(),
                seconds.apply(run.steps().get(STEPS.get(0))), seconds.apply(score),
                seconds.apply(run.steps().get(STEPS.get(2))), seconds.apply(run.steps().get(STEPS.get(3))),
                seconds.apply(run.total()), run.scoreCommits(), products * 1000.0 / Math.max(1, score.toMillis())));
        }
        report.append("%n결과 checksum (모든 실행 같음): `%s`%n".formatted(runs.get(0).checksum()));
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);
        log.info("실험 결과\n{}", report);
    }
}
