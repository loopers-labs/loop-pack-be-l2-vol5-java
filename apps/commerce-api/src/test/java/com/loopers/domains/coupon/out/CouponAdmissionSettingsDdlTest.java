package com.loopers.domains.coupon.out;

import java.util.ArrayList;
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

// 테스트 스키마는 엔티티로 만들어 coupon_admission_settings가 이미 있다. 지운 뒤 스크립트를 실행해 엔티티가 만든 모양과 비교하고,
// 끝나면 엔티티가 만든 DDL로 되돌린다.
@SpringBootTest
class CouponAdmissionSettingsDdlTest {
    private static final String TABLE = "coupon_admission_settings";
    private static final String SCRIPT = "sql/create-coupon-admission-settings.sql";
    private static final String SEED = "sql/local/seed-coupon-admission-settings.sql";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    private String entityDdl;
    private List<Map<String, Object>> entityShape;

    @BeforeEach
    void dropEntityTable() {
        entityDdl = jdbcTemplate.queryForMap("show create table " + TABLE).get("Create Table").toString();
        entityShape = shape();
        assertThat(entityShape).isNotEmpty();
        jdbcTemplate.execute("drop table " + TABLE);
    }

    @AfterEach
    void restoreEntityTable() {
        jdbcTemplate.execute("drop table if exists " + TABLE);
        jdbcTemplate.execute(entityDdl);
    }

    @Test
    @DisplayName("테이블이 없을 때 스크립트를 실행하면 엔티티와 같은 열·타입·NULL 여부·기본 키가 생기고, 다시 실행해도 같다")
    void createsTableLikeEntityAndIsRerunnable() {
        run(SCRIPT);
        assertThat(shape()).isEqualTo(entityShape);

        run(SCRIPT);
        assertThat(shape()).isEqualTo(entityShape);
    }

    @Test
    @DisplayName("로컬 seed는 닫힌 수용 설정 행 하나(capacity 3, open false)를 넣고, 다시 실행해도 행은 하나다")
    void localSeedInsertsClosedSettings() {
        run(SCRIPT);

        run(SEED);
        run(SEED);

        assertThat(shape()).as("스크립트로 만든 " + TABLE).isNotEmpty();
        assertThat(jdbcTemplate.queryForList("select id, capacity, open from " + TABLE)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("id", 1L).containsEntry("capacity", 3).containsEntry("open", false));
    }

    private List<Map<String, Object>> shape() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(jdbcTemplate.queryForList(
            "select column_name, column_type, is_nullable, column_key, extra from information_schema.columns"
                + " where table_schema = database() and table_name = ? order by column_name", TABLE));
        rows.addAll(jdbcTemplate.queryForList("select index_name, seq_in_index, column_name, non_unique from information_schema.statistics"
            + " where table_schema = database() and table_name = ? order by index_name, seq_in_index", TABLE));
        return rows;
    }

    private void run(String script) {
        new ResourceDatabasePopulator(new ClassPathResource(script)).execute(dataSource);
    }
}
