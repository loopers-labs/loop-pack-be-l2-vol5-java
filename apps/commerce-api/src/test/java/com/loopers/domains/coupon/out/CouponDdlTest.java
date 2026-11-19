package com.loopers.domains.coupon.out;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

// 테스트 스키마는 엔티티로 만들어 coupon_campaign·issued_coupon이 이미 있다. 운영 DB처럼 테이블이 없는 상태를 만들려고 지운 뒤
// 스크립트를 실행하고, 결과를 엔티티가 만든 모양(열·유일 제약·인덱스·CHECK)과 비교한다. 끝나면 엔티티가 만든 DDL로 되돌린다.
@SpringBootTest
class CouponDdlTest {
    private static final String SCRIPT = "sql/create-coupon.sql";
    private static final String SEED = "sql/local/seed-coupon-campaign.sql";
    private static final List<String> TABLES = List.of("issued_coupon", "coupon_campaign");

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    private Map<String, String> entityDdl;
    private Map<String, List<Map<String, Object>>> entityShape;

    @BeforeEach
    void dropEntityTables() {
        entityDdl = new LinkedHashMap<String, String>();
        entityShape = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : TABLES) {
            entityDdl.put(table, jdbcTemplate.queryForMap("show create table " + table).get("Create Table").toString());
            entityShape.put(table, shape(table));
            assertThat(entityShape.get(table)).isNotEmpty();
            jdbcTemplate.execute("drop table " + table);
        }
    }

    @AfterEach
    void restoreEntityTables() {
        for (String table : TABLES) {
            jdbcTemplate.execute("drop table if exists " + table);
            jdbcTemplate.execute(entityDdl.get(table));
        }
    }

    @Test
    @DisplayName("테이블이 없을 때 스크립트를 실행하면 엔티티와 같은 열·타입·NULL 여부·유일 제약·목록 인덱스·발급 수 CHECK가 생기고, 다시 실행해도 같다")
    void createsTablesLikeEntityAndIsRerunnable() {
        run(SCRIPT);
        assertSameAsEntity();

        run(SCRIPT);
        assertSameAsEntity();
    }

    @Test
    @DisplayName("로컬 seed 스크립트는 스크립트로 만든 테이블에 발급 가능한 캠페인을 넣는다")
    void localSeedInsertsIssuableCampaign() {
        run(SCRIPT);

        run(SEED);

        assertThat(shape("coupon_campaign")).as("스크립트로 만든 coupon_campaign").isNotEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from coupon_campaign where active and issued_count < total_quota",
            Long.class)).isPositive();
    }

    private void assertSameAsEntity() {
        for (String table : TABLES) {
            assertThat(shape(table)).as(table).isEqualTo(entityShape.get(table));
        }
        assertThat(jdbcTemplate.queryForList("select column_name from information_schema.statistics where table_schema = database()"
                + " and table_name = 'issued_coupon' and index_name = 'idx_issued_coupon_user_issued' order by seq_in_index", String.class))
            .containsExactly("user_id", "issued_at", "id");
        assertThat(jdbcTemplate.queryForList("select column_name from information_schema.statistics where table_schema = database()"
                + " and table_name = 'issued_coupon' and index_name = 'uk_issued_coupon_campaign_user' and non_unique = 0"
                + " order by seq_in_index", String.class))
            .containsExactly("campaign_id", "user_id");
        assertThat(jdbcTemplate.queryForList("select constraint_name from information_schema.table_constraints where"
                + " table_schema = database() and table_name = 'coupon_campaign' and constraint_type = 'CHECK'", String.class))
            .containsExactly("chk_coupon_campaign_issued");
    }

    // 열(이름·타입·NULL·키·extra)과 인덱스(이름·열 순서·유일 여부)를 함께 본다.
    private List<Map<String, Object>> shape(String table) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(jdbcTemplate.queryForList(
            "select column_name, column_type, is_nullable, column_key, extra from information_schema.columns"
                + " where table_schema = database() and table_name = ? order by column_name", table));
        rows.addAll(jdbcTemplate.queryForList("select index_name, seq_in_index, column_name, non_unique from information_schema.statistics"
            + " where table_schema = database() and table_name = ? order by index_name, seq_in_index", table));
        return rows;
    }

    private void run(String script) {
        new ResourceDatabasePopulator(new ClassPathResource(script)).execute(dataSource);
    }
}
