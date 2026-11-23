package com.loopers.domain.product.entity;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 엔티티가 만든 스키마가 "기간마다 공개본은 하나"를 DB 에서 지키는지 확인한다 (9주차 D-180, D-183). 쓰기는 batch 처럼 JDBC 로 한다 */
@SpringBootTest
class WeeklyRankingSnapshotSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("같은 기간에 PUBLISHED 가 이미 있으면, 다른 revision 을 PUBLISHED 로 저장할 수 없다.")
    @Test
    void rejectsSecondPublishedInSamePeriod() {
        // arrange
        insertSnapshot("weekly-20260817-r1", "2026-08-17", 1, "PUBLISHED");

        // act & assert
        assertThrows(DuplicateKeyException.class, () -> insertSnapshot("weekly-20260817-r2", "2026-08-17", 2, "PUBLISHED"));
    }

    @DisplayName("PUBLISHED 가 아닌 snapshot 은 같은 기간에 여럿 둘 수 있고, 다른 기간의 공개본과는 겹치지 않는다.")
    @Test
    void allowsUnpublishedAndOtherPeriods() {
        // act
        insertSnapshot("weekly-20260817-r1", "2026-08-17", 1, "PUBLISHED");
        insertSnapshot("weekly-20260817-r2", "2026-08-17", 2, "COMPLETED");
        insertSnapshot("weekly-20260817-r3", "2026-08-17", 3, "FROZEN");
        insertSnapshot("weekly-20260824-r1", "2026-08-24", 1, "PUBLISHED");

        // assert
        assertThat(jdbc.queryForObject("select count(*) from weekly_ranking_snapshot", Integer.class)).isEqualTo(4);
    }

    @DisplayName("한 TX 에서 기존 공개본을 COMPLETED 로 내린 뒤 새 revision 을 올리면, 공개본이 바뀐다.")
    @Test
    void switchesPublishedRevision() {
        // arrange
        insertSnapshot("weekly-20260817-r1", "2026-08-17", 1, "PUBLISHED");
        insertSnapshot("weekly-20260817-r2", "2026-08-17", 2, "COMPLETED");

        // act
        jdbc.update("update weekly_ranking_snapshot set status = 'COMPLETED' where snapshot_id = 'weekly-20260817-r1'");
        jdbc.update("update weekly_ranking_snapshot set status = 'PUBLISHED' where snapshot_id = 'weekly-20260817-r2'");

        // assert
        assertAll(
            () -> assertThat(publishedPeriodOf("weekly-20260817-r1")).isNull(),
            () -> assertThat(publishedPeriodOf("weekly-20260817-r2")).isEqualTo("2026-08-17")
        );
    }

    private void insertSnapshot(String snapshotId, String periodStart, int revision, String status) {
        jdbc.update("""
            insert into weekly_ranking_snapshot
                (snapshot_id, period_start, revision, status, frozen_at, input_count, input_checksum, created_at, updated_at)
            values (?, ?, ?, ?, '2026-08-24 00:05:00', 0, 'checksum', now(6), now(6))""",
            snapshotId, periodStart, revision, status);
    }

    private String publishedPeriodOf(String snapshotId) {
        return jdbc.queryForObject(
            "select cast(published_period as char) from weekly_ranking_snapshot where snapshot_id = ?", String.class, snapshotId);
    }
}
