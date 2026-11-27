package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.support.ranking.OwnedRankingBatchProcess;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the production weekly job in a separate JVM against this incident's dedicated purchase activity DB. */
final class CheckoutIncidentRanking {
    private static final DateTimeFormatter NANO_INSTANT = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private final MySQLContainer<?> database;
    private final JdbcTemplate activity;
    private final TestRestTemplate customer;
    private final LocalDate week;

    CheckoutIncidentRanking(MySQLContainer<?> database, JdbcTemplate activity, TestRestTemplate customer, LocalDate week) {
        this.database = database;
        this.activity = activity;
        this.customer = customer;
        this.week = week;
    }

    void initialize() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/ranking/001-weekly-ranking-input.sql"),
            new ClassPathResource("fixtures/ranking/002-weekly-ranking-score.sql"),
            new ClassPathResource("fixtures/ranking/003-weekly-ranking-publication.sql")).execute(activity.getDataSource());
        OwnedRankingBatchProcess.initializeSchema(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    void assertUnpublished() {
        var response = rankingResponse();
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().at("/meta/errorCode").asText()).isEqualTo("RANKING_NOT_PUBLISHED");
    }

    Publication publish(int revision, long productId, Map<Long, Instant> expectedOrders) throws Exception {
        var result = OwnedRankingBatchProcess.run(database.getJdbcUrl(), database.getUsername(), database.getPassword(),
            week, revision, null);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.stepNames()).containsExactly("freezeWeeklyInput", "calculateWeeklyScores", "publishWeeklyRanking");
        String id = "weekly-" + week.format(DateTimeFormatter.BASIC_ISO_DATE) + "-r" + revision;
        assertThat(result.snapshotId()).isEqualTo(id);
        var expectedInputs = new TreeMap<>(expectedOrders).entrySet().stream()
            .map(order -> new FrozenItem(order.getKey(), productId, 1, order.getValue())).toList();
        String inputChecksum = sha256(expectedInputs.stream().map(FrozenItem::checksumLine).collect(Collectors.joining()));
        long score = expectedInputs.size();
        String resultChecksum = sha256(productId + "\t" + score + "\t1\n");

        var snapshot = stored(id);
        assertThat(snapshot.inputs()).as("rank input compared with each original confirmed order").isEqualTo(expectedInputs);
        assertThat(snapshot.results()).containsExactly(new RankedItem(productId, score, 1));
        assertThat(snapshot.inputChecksum()).isEqualTo(result.inputChecksum()).isEqualTo(inputChecksum);
        assertThat(snapshot.resultChecksum()).isEqualTo(result.resultChecksum()).isEqualTo(resultChecksum);
        assertThat(snapshot.inputCount()).isEqualTo(score);
        assertThat(snapshot.productCount()).isEqualTo(1);
        assertThat(snapshot.resultCount()).isEqualTo(1);

        var response = rankingResponse();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body.at("/meta/result").asText()).isEqualTo("SUCCESS");
        JsonNode data = body.required("data");
        assertThat(data.required("status").asText()).isEqualTo("PUBLISHED");
        assertThat(data.required("snapshotId").asText()).isEqualTo(id);
        assertThat(data.required("revision").asInt()).isEqualTo(revision);
        assertThat(data.required("periodStart").asText()).isEqualTo(week.toString());
        assertThat(data.required("periodEnd").asText()).isEqualTo(week.plusWeeks(1).toString());
        assertThat(Instant.parse(data.required("frozenAt").asText())).isEqualTo(snapshot.frozenAt());
        assertThat(data.required("items")).hasSize(1);
        JsonNode item = data.required("items").required(0);
        assertThat(item.required("productId").asLong()).isEqualTo(productId);
        assertThat(item.required("score").asLong()).isEqualTo(score);
        assertThat(item.required("rank").asLong()).isEqualTo(1L);
        return new Publication(id, revision, score, result.jobInstanceId(), result.jobExecutionId(), snapshot, body);
    }

    void assertStillPublished(Publication prior) {
        var current = rankingResponse();
        assertThat(current.getStatusCode().value()).isEqualTo(200);
        assertThat(current.getBody()).isEqualTo(prior.httpBody());
    }

    void assertSnapshotUnchanged(Publication prior) {
        assertThat(stored(prior.snapshotId())).as("prior snapshot after newer publication").isEqualTo(prior.snapshot());
    }

    private ResponseEntity<JsonNode> rankingResponse() {
        return customer.getForEntity("/api/v1/rankings/weekly?periodStart={week}&limit=10", JsonNode.class, week);
    }

    private Snapshot stored(String id) {
        List<FrozenItem> inputs = activity.query("""
            SELECT order_id, product_id, quantity, confirmed_at_second, confirmed_at_nano
            FROM weekly_ranking_input WHERE snapshot_id=? ORDER BY order_id, product_id
            """, (row, n) -> new FrozenItem(row.getLong("order_id"), row.getLong("product_id"),
            row.getInt("quantity"), Instant.ofEpochSecond(row.getLong("confirmed_at_second"), row.getInt("confirmed_at_nano"))), id);
        List<RankedItem> results = activity.query("""
            SELECT product_id, score, ranking_position FROM weekly_ranking_result WHERE snapshot_id=? ORDER BY ranking_position
            """, (row, n) -> new RankedItem(row.getLong("product_id"), row.getLong("score"), row.getLong("ranking_position")), id);
        return activity.queryForObject("""
            SELECT frozen_at_second, frozen_at_nano, input_count, product_count, result_count, input_checksum, result_checksum
            FROM weekly_ranking_snapshot WHERE snapshot_id=? AND input_state='FROZEN' AND calculation_state='COMPLETE'
            """, (row, n) -> new Snapshot(Instant.ofEpochSecond(row.getLong("frozen_at_second"), row.getInt("frozen_at_nano")),
            row.getLong("input_count"), row.getLong("product_count"), row.getLong("result_count"), row.getString("input_checksum"),
            row.getString("result_checksum"), inputs, results), id);
    }

    private static String sha256(String expected) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected.getBytes(StandardCharsets.UTF_8)));
    }

    record Publication(String snapshotId, int revision, long score, long jobInstanceId, long jobExecutionId,
                       Snapshot snapshot, JsonNode httpBody) { }

    record Snapshot(Instant frozenAt, long inputCount, long productCount, long resultCount, String inputChecksum,
                    String resultChecksum, List<FrozenItem> inputs, List<RankedItem> results) { }

    record FrozenItem(long orderId, long productId, int quantity, Instant confirmedAt) {
        String checksumLine() {
            return orderId + "\t" + productId + "\t" + quantity + "\t" + NANO_INSTANT.format(confirmedAt) + "\n";
        }
    }

    record RankedItem(long productId, long score, long rank) { }
}
