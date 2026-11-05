package com.loopers.infrastructure.order;

import com.loopers.utils.DatabaseCleanUp;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

// 테스트 스키마는 엔티티로 만들어 confirmed_at 열·인덱스가 이미 있다. 열 추가 전 운영 DB 모양을 재현하려고 둘을 지운 뒤
// 스크립트를 실행한다. 같은 컨텍스트의 다른 테스트가 원래 스키마를 보도록 끝나면 엔티티 모양으로 되돌린다.
@SpringBootTest
class OrderConfirmedAtMigrationTest {
    private static final String SCRIPT = "sql/add-order-confirmed-at.sql";
    private static final String INDEX = "idx_orders_status_confirmed_at";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleanUp cleanup;
    private List<Map<String, Object>> entityColumn;

    @BeforeEach
    void prepareSchemaBeforeConfirmedAt() {
        entityColumn = confirmedAtColumn();
        assertThat(entityColumn).hasSize(1);
        jdbcTemplate.execute("alter table orders drop index " + INDEX + ", drop column confirmed_at");
        // 옛 확정 주문(과거 포인트·이전 PG 확정)과 확정 전 주문
        order(1, "CONFIRMED");
        order(2, "PAYMENT_PENDING");
        order(3, "DRAFT");
    }

    @AfterEach
    void clean() {
        if (confirmedAtColumn().isEmpty()) {
            jdbcTemplate.execute("alter table orders add column confirmed_at datetime(6)");
        }
        if (indexColumns().isEmpty()) {
            jdbcTemplate.execute("create index " + INDEX + " on orders (status, confirmed_at)");
        }
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("스크립트는 엔티티와 같은 확정 시각 열·(status, confirmed_at) 인덱스를 만들고 옛 CONFIRMED 주문의 확정 시각을 채우지 않는다")
    void addsColumnAndIndexWithoutBackfill() {
        run();

        assertThat(confirmedAtColumn()).isEqualTo(entityColumn);
        assertThat(indexColumns()).containsExactly("status", "confirmed_at");
        assertThat(jdbcTemplate.queryForObject("select count(*) from orders where confirmed_at is not null", Long.class)).isZero();
    }

    @Test
    @DisplayName("스크립트를 다시 실행해도 오류 없이 열·인덱스가 한 번 실행한 결과와 같다")
    void rerunKeepsSameResult() {
        run();

        run();

        assertThat(confirmedAtColumn()).isEqualTo(entityColumn);
        assertThat(indexColumns()).containsExactly("status", "confirmed_at");
        assertThat(jdbcTemplate.queryForObject("select count(*) from orders", Long.class)).isEqualTo(3);
    }

    private void order(long id, String status) {
        jdbcTemplate.update("insert into orders(id, user_id, total, status) values (?, 1, 1000, ?)", id, status);
    }

    private void run() {
        new ResourceDatabasePopulator(new ClassPathResource(SCRIPT)).execute(dataSource);
    }

    private List<Map<String, Object>> confirmedAtColumn() {
        return jdbcTemplate.queryForList("select column_type, is_nullable from information_schema.columns"
            + " where table_schema = database() and table_name = 'orders' and column_name = 'confirmed_at'");
    }

    private List<String> indexColumns() {
        return jdbcTemplate.queryForList("select column_name from information_schema.statistics where table_schema = database()"
            + " and table_name = 'orders' and index_name = ? order by seq_in_index", String.class, INDEX);
    }
}
