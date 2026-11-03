package com.loopers.domains.order.out;

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

// 테스트 스키마는 엔티티로 만들어 order_outbox·outbox_relay_settings가 이미 있다. 운영 DB처럼 테이블이 없는 상태를 만들려고 지운 뒤
// 스크립트를 실행하고, 결과를 엔티티가 만든 모양과 비교한다. 같은 컨텍스트의 다른 테스트가 원래 스키마를 보도록 끝나면 엔티티가 만든 DDL로 되돌린다.
@SpringBootTest
class OrderOutboxDdlTest {
    private static final String SCRIPT = "sql/create-order-outbox.sql";
    private static final String UNIQUE = "uk_order_outbox_event_id";
    private static final String INDEX = "idx_order_outbox_status_next_attempt";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    private String entityDdl;
    private List<Map<String, Object>> entityColumns;
    private String settingsEntityDdl;
    private List<Map<String, Object>> settingsEntityColumns;

    @BeforeEach
    void dropEntityTable() {
        entityDdl = jdbcTemplate.queryForMap("show create table order_outbox").get("Create Table").toString();
        entityColumns = columns("order_outbox");
        assertThat(entityColumns).isNotEmpty();
        settingsEntityDdl = jdbcTemplate.queryForMap("show create table outbox_relay_settings").get("Create Table").toString();
        settingsEntityColumns = columns("outbox_relay_settings");
        assertThat(settingsEntityColumns).isNotEmpty();
        jdbcTemplate.execute("drop table order_outbox");
        jdbcTemplate.execute("drop table outbox_relay_settings");
    }

    @AfterEach
    void restoreEntityTable() {
        jdbcTemplate.execute("drop table if exists order_outbox");
        jdbcTemplate.execute(entityDdl);
        jdbcTemplate.execute("drop table if exists outbox_relay_settings");
        jdbcTemplate.execute(settingsEntityDdl);
    }

    @Test
    @DisplayName("테이블이 없을 때 스크립트를 실행하면 엔티티와 같은 열·타입·NULL 여부와 event_id 유일 제약·(status, next_attempt_at, id) 인덱스가 생긴다")
    void createsTableLikeEntity() {
        run();

        assertSameAsEntity();
    }

    @Test
    @DisplayName("스크립트를 두 번 실행해도 오류 없이 한 번 실행한 결과와 같고 이미 있던 Outbox 행을 지우지 않는다")
    void rerunKeepsSameResult() {
        run();
        jdbcTemplate.update("insert into order_outbox(event_id, order_id, event_type, schema_version, message_key, payload, status,"
            + " attempt_count, next_attempt_at, created_at) values ('order-confirmed:v1:1', 1, 'OrderConfirmed', 1, '1', '{}',"
            + " 'PENDING', 0, '2026-10-29 01:00:00', '2026-10-29 01:00:00')");

        run();

        assertSameAsEntity();
        assertThat(jdbcTemplate.queryForObject("select count(*) from order_outbox where event_id = 'order-confirmed:v1:1'",
            Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("1단계 모양(row_failure_count 없음)의 테이블에 스크립트를 실행하면 열이 0 기본값으로 추가되어 엔티티와 같아지고 기존 행은 남는다")
    void addsRowFailureCountToStageOneTable() {
        jdbcTemplate.execute(entityDdl);
        jdbcTemplate.execute("alter table order_outbox drop column row_failure_count");
        jdbcTemplate.update("insert into order_outbox(event_id, order_id, event_type, schema_version, message_key, payload, status,"
            + " attempt_count, next_attempt_at, created_at) values ('order-confirmed:v1:1', 1, 'OrderConfirmed', 1, '1', '{}',"
            + " 'PENDING', 0, '2026-10-29 01:00:00', '2026-10-29 01:00:00')");

        run();

        assertSameAsEntity();
        assertThat(jdbcTemplate.queryForObject("select row_failure_count from order_outbox where event_id = 'order-confirmed:v1:1'",
            Integer.class)).isZero();
    }

    @Test
    @DisplayName("S6: 스크립트는 엔티티와 같은 outbox_relay_settings와 기본 행 1개(묶음 100·최대 10초·켜짐)를 만든다")
    void createsRelaySettingsWithDefaultRow() {
        run();

        assertThat(columns("outbox_relay_settings")).isEqualTo(settingsEntityColumns);
        assertThat(jdbcTemplate.queryForList("select id, batch_size, max_round_millis, enabled from outbox_relay_settings"))
            .singleElement().satisfies(row -> assertThat(row)
                .containsEntry("id", 1L)
                .containsEntry("batch_size", 100)
                .containsEntry("max_round_millis", 10_000)
                .containsEntry("enabled", true));
    }

    @Test
    @DisplayName("S6: 사람이 바꾼 relay 설정 값은 스크립트를 다시 실행해도 기본값으로 덮이지 않고 행은 1개다")
    void rerunKeepsChangedRelaySettings() {
        run();
        assertThat(columns("outbox_relay_settings")).isEqualTo(settingsEntityColumns);
        jdbcTemplate.update("update outbox_relay_settings set batch_size = 7 where id = 1");

        run();

        assertThat(jdbcTemplate.queryForList("select batch_size from outbox_relay_settings", Integer.class)).containsExactly(7);
    }

    private void assertSameAsEntity() {
        assertThat(columns("order_outbox")).isEqualTo(entityColumns);
        assertThat(column("payload")).containsEntry("column_type", "text").containsEntry("is_nullable", "NO");
        assertThat(column("next_attempt_at")).containsEntry("column_type", "datetime(6)").containsEntry("is_nullable", "NO");
        assertThat(indexColumns(UNIQUE, 0)).containsExactly("event_id");
        assertThat(indexColumns(INDEX, 1)).containsExactly("status", "next_attempt_at", "id");
    }

    private void run() {
        new ResourceDatabasePopulator(new ClassPathResource(SCRIPT)).execute(dataSource);
    }

    private List<Map<String, Object>> columns(String table) {
        return jdbcTemplate.queryForList("select column_name, column_type, is_nullable, column_key, extra"
            + " from information_schema.columns where table_schema = database() and table_name = ? order by column_name", table);
    }

    private Map<String, Object> column(String name) {
        return columns("order_outbox").stream().filter(column -> name.equals(column.get("column_name"))).findFirst().orElse(Map.of());
    }

    private List<String> indexColumns(String index, int nonUnique) {
        return jdbcTemplate.queryForList("select column_name from information_schema.statistics where table_schema = database()"
            + " and table_name = 'order_outbox' and index_name = ? and non_unique = ? order by seq_in_index",
            String.class, index, nonUnique);
    }
}
