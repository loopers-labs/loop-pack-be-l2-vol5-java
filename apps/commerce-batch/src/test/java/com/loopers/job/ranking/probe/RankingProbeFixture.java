package com.loopers.job.ranking.probe;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

// 9주차 1단계 probe. 과제 §3 "세 상품으로 DB chunk 재시작 연결하기" 예시를
// 테스트 안에서 그대로 조립한다(제품 코드·진입점 없음).
// 테이블 두 개는 테스트 준비에서만 만들고 지운다.
// Batch metadata는 지우지 않으므로 snapshotId를 테스트마다 새로 만든다.
final class RankingProbeFixture {
    static final String JOB = "purchaseInputProbe";
    static final String STEP = "probeProducts";
    static final String READER = "purchaseProductProbe";
    static final String READ_COUNT_KEY = READER + ".read.count";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    RankingProbeFixture(JobRepository jobRepository, PlatformTransactionManager transactionManager, DataSource dataSource) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    void createTables() {
        jdbcTemplate.execute("create table if not exists ranking_snapshot_input (snapshot_id varchar(100) not null,"
            + " order_id bigint not null, product_id bigint not null, quantity int not null, confirmed_at datetime(6) not null,"
            + " primary key (snapshot_id, order_id, product_id))");
        jdbcTemplate.execute("create table if not exists batch_restart_probe (snapshot_id varchar(100) not null,"
            + " product_id bigint not null, primary key (snapshot_id, product_id))");
    }

    void dropTables() {
        jdbcTemplate.execute("drop table if exists ranking_snapshot_input");
        jdbcTemplate.execute("drop table if exists batch_restart_probe");
    }

    // 과제 §6 입력 4행(주문·상품·수량). 확정 시각은 UTC다. probe reader는 상품 ID만 읽는다.
    void insertInput(String snapshotId) {
        Object[][] rows = {
            {42L, 7L, 2, "2026-11-16T01:00:00"},
            {42L, 8L, 1, "2026-11-16T01:00:00"},
            {43L, 7L, 3, "2026-11-16T02:00:00"},
            {44L, 9L, 5, "2026-11-16T03:00:00"}
        };
        for (Object[] row : rows) {
            jdbcTemplate.update("insert into ranking_snapshot_input(snapshot_id, order_id, product_id, quantity, confirmed_at)"
                + " values (?, ?, ?, ?, ?)", snapshotId, row[0], row[1], row[2],
                Timestamp.valueOf(LocalDateTime.parse((String) row[3])));
        }
    }

    // 상품 ID를 순서대로 읽는다. saveState로 읽은 위치를 ExecutionContext(read.count)에 남겨
    // 재시작 때 그 뒤부터 읽는다.
    JdbcCursorItemReader<Long> reader(String snapshotId) {
        return new JdbcCursorItemReaderBuilder<Long>()
            .name(READER)
            .dataSource(dataSource)
            .sql("select distinct product_id from ranking_snapshot_input where snapshot_id = ? order by product_id")
            .preparedStatementSetter(statement -> statement.setString(1, snapshotId))
            .rowMapper((resultSet, rowNumber) -> resultSet.getLong(1))
            .saveState(true)
            .build();
    }

    // 결과 테이블에 INSERT한다(upsert 아님). 같은 상품을 다시 쓰면 PK 충돌로 드러난다.
    // failOnProduct가 들어오면 그 상품에서 실패한다.
    ItemWriter<Long> insertWriter(String snapshotId, Long failOnProduct, Consumer<Chunk<? extends Long>> beforeWrite) {
        return chunk -> {
            beforeWrite.accept(chunk);
            for (Long productId : chunk) {
                if (productId.equals(failOnProduct)) {
                    throw new IllegalStateException("주입된 쓰기 실패 product=" + productId);
                }
                jdbcTemplate.update("insert into batch_restart_probe(snapshot_id, product_id) values (?, ?)", snapshotId,
                    productId);
            }
        };
    }

    Job job(String snapshotId, ItemWriter<Long> writer, ItemStream extraStream) {
        SimpleStepBuilder<Long, Long> step = new StepBuilder(STEP, jobRepository)
            .<Long, Long>chunk(2, transactionManager)
            .reader(reader(snapshotId))
            .writer(writer);
        if (extraStream != null) {
            step.stream(extraStream);
        }
        Step built = step.build();
        return new JobBuilder(JOB, jobRepository).start(built).build();
    }

    // 서비스 트랜잭션과 섞이지 않게 풀에서 연결을 새로 받아 결과 행을 읽는다.
    List<Long> resultRows(String snapshotId) throws SQLException {
        List<Long> productIds = new ArrayList<Long>();
        try (Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                "select product_id from batch_restart_probe where snapshot_id = ? order by product_id")) {
            statement.setString(1, snapshotId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    productIds.add(resultSet.getLong(1));
                }
            }
        }
        return productIds;
    }
}
