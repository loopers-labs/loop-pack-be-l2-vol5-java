package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.step.SnapshotProductPagingReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 점수 Reader가 재시작 때 페이지 크기를 검사하는 이유(ADR 0095).
 * 같은 위치 정보로 페이지 크기만 바꿔 다시 열었을 때 Spring Batch 기본 Reader와 점수 Reader가 어떻게 다른지 본다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME
})
class SnapshotProductPagingReaderTest {
    private static final String SNAPSHOT_ID = "page-size-check";

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM ranking_snapshot_input WHERE snapshot_id = ?", SNAPSHOT_ID);
        for (long product = 1; product <= 6; product++) {
            jdbc.update("INSERT INTO ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at) "
                + "VALUES (?, ?, ?, 1, '2026-08-17 01:00:00')", SNAPSHOT_ID, product, product);
        }
    }

    @DisplayName("기본 페이지 Reader는 크기 2로 상품 4개를 읽은 위치에서 크기 3으로 다시 열면 상품을 건너뛴다.")
    @Test
    void defaultPagingReaderSkipsProductWhenPageSizeChanges() throws Exception {
        ExecutionContext context = new ExecutionContext();
        assertThat(readAndSave(plainReader(2), context, 4)).containsExactly(1L, 2L, 3L, 4L);

        List<Long> rest = readAll(plainReader(3), context);

        assertThat(rest).doesNotContain(5L).containsExactly(6L);
    }

    @DisplayName("점수 Reader는 같은 상황에서 읽기 전에 거부하고, 원래 크기로 열면 남은 상품을 빠짐없이 읽는다.")
    @Test
    void snapshotReaderRejectsChangedPageSize() throws Exception {
        ExecutionContext context = new ExecutionContext();
        assertThat(readAndSave(snapshotReader(2), context, 4)).containsExactly(1L, 2L, 3L, 4L);

        SnapshotProductPagingReader changed = snapshotReader(3);
        assertThatThrownBy(() -> changed.open(context))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("원래 chunk 크기로");

        assertThat(readAll(snapshotReader(2), context)).containsExactly(5L, 6L);
    }

    private List<Long> readAndSave(ItemStreamReader<Long> reader, ExecutionContext context, int count) throws Exception {
        reader.open(context);
        List<Long> read = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            read.add(reader.read());
        }
        reader.update(context);
        reader.close();
        return read;
    }

    private List<Long> readAll(ItemStreamReader<Long> reader, ExecutionContext context) throws Exception {
        reader.open(context);
        List<Long> read = new ArrayList<>();
        for (Long item = reader.read(); item != null; item = reader.read()) {
            read.add(item);
        }
        reader.close();
        return read;
    }

    private SnapshotProductPagingReader snapshotReader(int pageSize) throws Exception {
        var reader = new SnapshotProductPagingReader(WeeklyRankingJobConfig.PRODUCT_READER, dataSource, SNAPSHOT_ID, pageSize);
        reader.afterPropertiesSet();
        return reader;
    }

    /** 점수 Reader와 같은 SQL·키·이름이지만 페이지 크기 검사가 없는 Spring Batch 기본 Reader. */
    private JdbcPagingItemReader<Long> plainReader(int pageSize) throws Exception {
        var provider = new MySqlPagingQueryProvider();
        provider.setSelectClause("DISTINCT product_id");
        provider.setFromClause("ranking_snapshot_input FORCE INDEX (idx_ranking_snapshot_input_product)");
        provider.setWhereClause("snapshot_id = :snapshotId");
        provider.setSortKeys(Map.of("product_id", Order.ASCENDING));

        var reader = new JdbcPagingItemReader<Long>();
        reader.setName(WeeklyRankingJobConfig.PRODUCT_READER);
        reader.setDataSource(dataSource);
        reader.setQueryProvider(provider);
        reader.setParameterValues(Map.of("snapshotId", SNAPSHOT_ID));
        reader.setPageSize(pageSize);
        reader.setRowMapper((rs, n) -> rs.getLong("product_id"));
        reader.afterPropertiesSet();
        return reader;
    }
}
