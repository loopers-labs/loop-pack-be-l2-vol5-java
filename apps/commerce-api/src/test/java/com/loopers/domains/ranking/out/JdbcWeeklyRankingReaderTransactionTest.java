package com.loopers.domains.ranking.out;

import com.loopers.domains.ranking.app.port.PublishedRanking;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

// R2: 0행일 때 두 번째 읽기(pointer 재조회)가 첫 읽기와 같은 읽기 전용 TX·같은 JDBC 연결에서 도는지 본다.
// api의 TM은 JPA TM이고 reader는 JdbcTemplate을 쓰므로, 둘이 같은 DataSource 연결에 묶이는지를 테스트 대역 JdbcTemplate이 쿼리마다 기록한다.
@SpringBootTest
@Import(JdbcWeeklyRankingReaderTransactionTest.RecordingConfig.class)
@Sql(scripts = "classpath:sql/ranking/create-ranking-tables.sql")
class JdbcWeeklyRankingReaderTransactionTest {
    private static final LocalDate PERIOD = LocalDate.parse("2026-08-17");
    private static final String SNAPSHOT = "weekly-20260817-r1";

    @Autowired private JdbcWeeklyRankingReader reader;
    @Autowired private RecordingJdbcTemplate recording;

    @AfterEach
    void clean() {
        recording.update("delete from ranking_publication");
        recording.update("delete from ranking_snapshot");
        recording.calls.clear();
    }

    @Test
    @DisplayName("공개본은 있는데 남은 행이 0이면 랭킹 읽기와 pointer 재조회가 같은 읽기 전용 TX·같은 JDBC 연결에서 돌고 PUBLISHED 빈 결과가 된다")
    void bothReadsShareOneReadOnlyTransaction() {
        recording.update("insert into ranking_snapshot(snapshot_id, period_start, revision, frozen_at, input_count, input_checksum)"
            + " values (?, ?, 1, ?, 0, ?)", SNAPSHOT, PERIOD, LocalDateTime.of(2026, 8, 24, 0, 10), "0".repeat(64));
        recording.update("insert into ranking_publication(period_start, snapshot_id) values (?, ?)", PERIOD, SNAPSHOT);
        recording.calls.clear();

        Optional<PublishedRanking> published = reader.findPublished(PERIOD, 10);

        assertThat(published).hasValueSatisfying(ranking -> {
            assertThat(ranking.snapshotId()).isEqualTo(SNAPSHOT);
            assertThat(ranking.products()).isEmpty();
        });
        assertThat(recording.calls).hasSize(2);
        assertThat(recording.calls).allSatisfy(call -> {
            assertThat(call.transactionActive()).isTrue();
            assertThat(call.readOnly()).isTrue();
        });
        assertThat(recording.calls.get(1).connection()).isSameAs(recording.calls.get(0).connection());
    }

    record Call(boolean transactionActive, boolean readOnly, Connection connection) { }

    // query(sql, RowCallbackHandler, args) 호출마다 그 시점의 TX 상태와 DataSourceUtils가 주는 연결(TX에 묶였으면 같은 객체)을 남긴다.
    static class RecordingJdbcTemplate extends JdbcTemplate {
        final List<Call> calls = new CopyOnWriteArrayList<Call>();

        RecordingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public void query(String sql, RowCallbackHandler handler, Object... args) {
            Connection connection = DataSourceUtils.getConnection(getDataSource());
            try {
                calls.add(new Call(TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly(), connection));
            } finally {
                DataSourceUtils.releaseConnection(connection, getDataSource());
            }
            super.query(sql, handler, args);
        }
    }

    @TestConfiguration
    static class RecordingConfig {
        @Bean
        @Primary
        RecordingJdbcTemplate recordingJdbcTemplate(DataSource dataSource) {
            return new RecordingJdbcTemplate(dataSource);
        }
    }
}
