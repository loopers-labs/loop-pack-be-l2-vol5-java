package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.support.preservation.PreservedMySql;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class WeeklyRankingPublicationSchemaTest {
    @Test
    @DisplayName("016 완성본·공개 pointer 증분 DDL이 실행 리소스로 제공된다")
    void providesIncrementalPublicationSchema() {
        assertThat(new ClassPathResource("preservation/016-weekly-ranking-publication.sql").exists()).isTrue();
    }

    @Test
    @DisplayName("016은 기존 입력·stage·metadata를 보존하며 완성본·순위 유일성·pointer 복합 FK를 보호한다")
    void preservesExistingDataAndEnforcesPublicationConstraints() throws Exception {
        String first = "00000000-0000-0000-0000-000000000001";
        String second = "00000000-0000-0000-0000-000000000002";
        try (var database = new PreservedMySql()) {
            database.prepareCommerceSchema();
            try (var connection = database.connect()) {
                for (String script : List.of("001-batch-metadata-mysql.sql", "014-weekly-ranking-snapshots.sql",
                    "015-weekly-ranking-scores.sql")) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/" + script));
                }
                execute(connection, "INSERT INTO weekly_ranking_snapshots VALUES ('" + first
                    + "','2026-08-17','2026-08-24',1,'FROZEN',1,'2026-08-24 00:01:00',1,REPEAT('a',64))");
                execute(connection, "INSERT INTO weekly_ranking_snapshot_inputs VALUES ('" + first + "',42,7,2,'2026-08-17 01:00:00')");
                execute(connection, "INSERT INTO weekly_ranking_score_stage VALUES ('" + first + "',7,2)");
                execute(connection, "INSERT INTO BATCH_JOB_INSTANCE VALUES (1,0,'preserved','existing')");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/016-weekly-ranking-publication.sql"));
                assertThat(rows(connection, "SELECT quantity FROM weekly_ranking_snapshot_inputs")).containsExactly("2");
                assertThat(rows(connection, "SELECT score FROM weekly_ranking_score_stage")).containsExactly("2");
                assertThat(rows(connection, "SELECT input_checksum FROM weekly_ranking_snapshots")).containsExactly("a".repeat(64));
                assertThat(rows(connection, "SELECT JOB_NAME FROM BATCH_JOB_INSTANCE")).containsExactly("preserved");
                assertThat(rows(connection, index("weekly_rankings", "uk_weekly_ranking_position")))
                    .containsExactly("snapshot_id", "ranking_position");
                assertThat(rows(connection, index("weekly_ranking_publications", "PRIMARY"))).containsExactly("period_start");
                assertThat(rows(connection, index("weekly_ranking_snapshots", "uk_weekly_snapshot_publication_contract")))
                    .containsExactly("period_start", "revision", "snapshot_id");
                execute(connection, "INSERT INTO weekly_rankings VALUES ('" + first + "',7,2,1)");
                assertRejected(connection, "INSERT INTO weekly_rankings VALUES ('" + first + "',7,2,2)");
                assertRejected(connection, "INSERT INTO weekly_rankings VALUES ('" + first + "',8,2,1)");
                assertRejected(connection, "INSERT INTO weekly_rankings VALUES ('" + first + "',8,0,2)");
                assertRejected(connection, "INSERT INTO weekly_rankings VALUES ('" + first + "',8,2,0)");
                assertRejected(connection, "INSERT INTO weekly_rankings VALUES ('" + second + "',7,2,1)");
                assertRejected(connection, "INSERT INTO weekly_ranking_publications VALUES ('2026-08-17','" + first + "',1)");
                assertRejected(connection, "INSERT INTO weekly_ranking_completions VALUES ('" + first + "',1,-1,REPEAT('a',64),UTC_TIMESTAMP(6))");
                assertRejected(connection, "INSERT INTO weekly_ranking_completions VALUES ('" + first + "',1,1,REPEAT('g',64),UTC_TIMESTAMP(6))");
                execute(connection, "INSERT INTO weekly_ranking_completions VALUES ('" + first + "',1,1,REPEAT('a',64),UTC_TIMESTAMP(6))");
                assertRejected(connection, "INSERT INTO weekly_ranking_publications VALUES ('2026-08-24','" + first + "',1)");
                assertRejected(connection, "INSERT INTO weekly_ranking_publications VALUES ('2026-08-17','" + first + "',2)");
                assertRejected(connection, "INSERT INTO weekly_ranking_publications VALUES ('2026-08-17','" + first + "',NULL)");
                execute(connection, "INSERT INTO weekly_ranking_publications VALUES ('2026-08-17','" + first + "',1)");
                assertThat(rows(connection, "SELECT snapshot_id FROM weekly_ranking_publications")).containsExactly(first);
                String plan = rows(connection, "EXPLAIN FORMAT=JSON SELECT product_id,score,ROW_NUMBER() OVER "
                    + "(ORDER BY score DESC,product_id ASC) FROM weekly_ranking_score_stage WHERE snapshot_id='" + first + "'").getFirst();
                assertThat(plan).contains("weekly_ranking_score_stage");
                System.out.println("C05 window EXPLAIN " + plan);
            }
        }
    }

    private String index(String table, String index) {
        return "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"
            + table + "' AND INDEX_NAME='" + index + "' AND NON_UNIQUE=0 ORDER BY SEQ_IN_INDEX";
    }

    private void assertRejected(Connection connection, String sql) {
        assertThatThrownBy(() -> execute(connection, sql)).isInstanceOf(SQLException.class);
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private List<String> rows(Connection connection, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
