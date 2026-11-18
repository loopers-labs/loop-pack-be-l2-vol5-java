package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.support.preservation.PreservedMySql;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class RankingSnapshotSchemaTest {
    private static final String SNAPSHOT = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "00000000-0000-0000-0000-000000000002";

    @Test
    @DisplayName("명시 snapshot DDL이 기간·revision과 입력의 유일성·FK·기본 불변 조건을 실제 DB에서 보호한다")
    void enforcesSnapshotStorageContract() throws Exception {
        var resource = new ClassPathResource("preservation/014-weekly-ranking-snapshots.sql");
        assertThat(resource.exists()).as("deliverable manifest and frozen-input DDL").isTrue();

        try (var database = new PreservedMySql()) {
            database.prepareCommerceSchema();
            try (var connection = database.connect()) {
                ScriptUtils.executeSqlScript(connection, resource);
                connection.setAutoCommit(false);
                insertManifest(connection, SNAPSHOT, "2026-08-17", "2026-08-24", 1, "a".repeat(64));
                execute(connection, """
                    INSERT INTO weekly_ranking_snapshot_inputs
                      (snapshot_id, order_id, product_id, quantity, confirmed_at)
                    VALUES ('%s', 42, 7, 2, '2026-08-17 01:00:00.123456')
                    """.formatted(SNAPSHOT));

                assertThatThrownBy(() -> insertManifest(connection, OTHER, "2026-08-17", "2026-08-24", 1,
                    "a".repeat(64))).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertManifest(connection, OTHER, "2026-08-18", "2026-08-25", 2,
                    "a".repeat(64))).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertManifest(connection, OTHER, "2026-08-17", "2026-08-24", 0,
                    "a".repeat(64))).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertManifest(connection, OTHER, "2026-08-17", "2026-08-31", 2,
                    "a".repeat(64))).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO weekly_ranking_snapshots
                      (snapshot_id, period_start, period_end, revision, state, input_contract_version)
                    VALUES ('%s', '2026-08-17', '2026-08-24', 2, 'FROZEN', 1)
                    """.formatted(OTHER))).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO weekly_ranking_snapshot_inputs
                      (snapshot_id, order_id, product_id, quantity, confirmed_at)
                    VALUES ('%s', 42, 7, 3, '2026-08-17 01:00:00.123456')
                    """.formatted(SNAPSHOT))).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO weekly_ranking_snapshot_inputs
                      (snapshot_id, order_id, product_id, quantity, confirmed_at)
                    VALUES ('%s', 43, 8, 0, '2026-08-17 01:00:00.123456')
                    """.formatted(SNAPSHOT))).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO weekly_ranking_snapshot_inputs
                      (snapshot_id, order_id, product_id, quantity, confirmed_at)
                    VALUES ('%s', 43, 8, 1, '2026-08-17 01:00:00.123456')
                    """.formatted(OTHER))).isInstanceOf(SQLException.class);
                connection.commit();
            }
            try (var connection = database.connect(); var statement = connection.createStatement();
                 var result = statement.executeQuery("""
                     SELECT s.input_count, i.quantity, i.confirmed_at
                     FROM weekly_ranking_snapshots s JOIN weekly_ranking_snapshot_inputs i
                       ON s.snapshot_id=i.snapshot_id
                     """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1);
                assertThat(result.getInt(2)).isEqualTo(2);
                assertThat(result.getString(3)).isEqualTo("2026-08-17 01:00:00.123456");
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void insertManifest(Connection connection, String id, String start, String end, int revision,
                                String checksum) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO weekly_ranking_snapshots
              (snapshot_id, period_start, period_end, revision, state, input_contract_version,
               frozen_at, input_count, input_checksum)
            VALUES (?, ?, ?, ?, 'FROZEN', 1, '2026-11-18 01:00:00.123456', 1, ?)
            """)) {
            statement.setString(1, id);
            statement.setString(2, start);
            statement.setString(3, end);
            statement.setInt(4, revision);
            statement.setString(5, checksum);
            statement.executeUpdate();
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
