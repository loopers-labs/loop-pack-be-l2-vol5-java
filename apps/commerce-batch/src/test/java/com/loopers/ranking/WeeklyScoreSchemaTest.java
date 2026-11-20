package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.support.preservation.PreservedMySql;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class WeeklyScoreSchemaTest {
    @Test
    @DisplayName("015가 기존 입력·metadata를 보존하고 stage 유일성·FK·양수와 covering 인덱스를 보호한다")
    void upgradesExistingSnapshotsWithoutChangingTheirContents() throws Exception {
        var incremental = new ClassPathResource("preservation/015-weekly-ranking-scores.sql");
        assertThat(incremental.exists()).as("deliverable incremental score schema").isTrue();
        try (var database = new PreservedMySql()) {
            database.prepareCommerceSchema();
            try (var connection = database.connect()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/001-batch-metadata-mysql.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/014-weekly-ranking-snapshots.sql"));
                execute(connection, """
                    INSERT INTO weekly_ranking_snapshots
                    (snapshot_id,period_start,period_end,revision,state,input_contract_version,frozen_at,input_count,input_checksum)
                    VALUES ('00000000-0000-0000-0000-000000000001','2026-08-17','2026-08-24',1,'FROZEN',1,
                        '2026-08-24 00:01:00',1,REPEAT('a',64))
                    """);
                execute(connection, """
                    INSERT INTO weekly_ranking_snapshot_inputs VALUES
                    ('00000000-0000-0000-0000-000000000001',42,7,2,'2026-08-17 01:00:00')
                    """);
                execute(connection, "INSERT INTO BATCH_JOB_INSTANCE VALUES (1,0,'preserved','existing')");

                ScriptUtils.executeSqlScript(connection, incremental);

                assertThat(rows(connection, "SELECT quantity FROM weekly_ranking_snapshot_inputs")).containsExactly("2");
                assertThat(rows(connection, "SELECT input_checksum FROM weekly_ranking_snapshots")).containsExactly("a".repeat(64));
                assertThat(rows(connection, "SELECT JOB_NAME FROM BATCH_JOB_INSTANCE")).containsExactly("preserved");
                assertThat(rows(connection, """
                    SELECT COLUMN_NAME FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='weekly_ranking_snapshot_inputs'
                        AND INDEX_NAME='ix_weekly_snapshot_input_product_quantity' AND NON_UNIQUE=1
                    ORDER BY SEQ_IN_INDEX
                    """)).containsExactly("snapshot_id", "product_id", "quantity");
                assertThat(rows(connection, """
                    SELECT COLUMN_NAME FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='weekly_ranking_snapshot_inputs' AND INDEX_NAME='PRIMARY'
                    ORDER BY SEQ_IN_INDEX
                    """)).containsExactly("snapshot_id", "order_id", "product_id");
                execute(connection, stage("00000000-0000-0000-0000-000000000001", 7, 4294967294L));
                assertThat(rows(connection, "SELECT score FROM weekly_ranking_score_stage")).containsExactly("4294967294");
                assertThatThrownBy(() -> execute(connection, stage("00000000-0000-0000-0000-000000000001", 7, 5)))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> execute(connection, stage("00000000-0000-0000-0000-000000000002", 8, 1)))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> execute(connection, stage("00000000-0000-0000-0000-000000000001", 8, 0)))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> execute(connection, stage("00000000-0000-0000-0000-000000000001", 0, 1)))
                    .isInstanceOf(SQLException.class);
            }
        }
    }

    private String stage(String snapshot, long product, long score) {
        return "INSERT INTO weekly_ranking_score_stage VALUES ('" + snapshot + "'," + product + "," + score + ")";
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private java.util.List<String> rows(Connection connection, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
