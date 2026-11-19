package com.loopers.interfaces.api.ranking.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.interfaces.api.ApiControllerAdvice;
import com.loopers.interfaces.api.ApiErrorMapper;
import com.loopers.support.preservation.PreservedMySql;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class WeeklyRankingApiTest {
    private static final String WEEK = "2026-08-17";
    private static final String WEEK_QUERY = "?periodStart=" + WEEK;
    private static final AtomicReference<Runnable> BEFORE_ITEM_QUERY = new AtomicReference<>();
    private static PreservedMySql database;
    private static ConfigurableApplicationContext application;
    private static URI endpoint;

    private final TestRestTemplate http = new TestRestTemplate();

    @BeforeAll
    static void startApplication() throws SQLException {
        database = new PreservedMySql();
        database.prepareCommerceSchema();
        try (var connection = database.connectAsAdministrator()) {
            for (var script : List.of("014-weekly-ranking-snapshots.sql", "015-weekly-ranking-scores.sql",
                "016-weekly-ranking-publication.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/ranking/" + script));
            }
        }
        application = new SpringApplicationBuilder(RankingConfiguration.class)
            .run(database.arguments("--spring.profiles.active=ranking-preserve,weekly-ranking-api-test",
                "--spring.main.banner-mode=off",
                "--datasource.mysql-jpa.main.transaction-isolation=TRANSACTION_READ_COMMITTED",
                "--server.port=0", "--management.server.port=-1"));
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        endpoint = URI.create("http://localhost:" + port + "/api/v1/rankings/weekly");
    }

    @AfterAll
    static void stopApplication() {
        try {
            if (application != null) {
                application.close();
            }
        } finally {
            if (database != null) {
                database.close();
            }
        }
    }

    @BeforeEach
    void resetFixtures() throws SQLException {
        BEFORE_ITEM_QUERY.set(null);
        try (var connection = database.connect()) {
            for (String table : List.of("weekly_ranking_publications", "weekly_rankings",
                "weekly_ranking_completions", "weekly_ranking_snapshots", "products", "brands")) {
                execute(connection, "DELETE FROM " + table);
            }
        }
    }

    @ParameterizedTest(name = "빈 pointer 행 저장 여부 {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("요청한 주가 미공개면 이전 주 공개본을 대체하지 않고 null 메타데이터와 빈 목록을 응답한다")
    void returnsUnpublishedWithoutFallback(boolean pointerExists) throws SQLException {
        try (var connection = database.connect()) {
            publish(connection, "2026-08-10", 1, completed(connection, "2026-08-10", 1, List.of()));
            if (pointerExists) {
                execute(connection, "INSERT INTO weekly_ranking_publications (period_start) VALUES (?)", WEEK);
            }
        }

        var data = success(WEEK_QUERY);

        assertThat(field(data, "periodStart")).isEqualTo(WEEK);
        assertThat(field(data, "periodEnd")).isEqualTo("2026-08-24");
        assertThat(field(data, "status")).isEqualTo("NOT_PUBLISHED");
        assertThat(data.get("snapshotId").isNull()).isTrue();
        assertThat(data.get("frozenAt").isNull()).isTrue();
        assertThat(data.path("items")).isEmpty();
        assertThat(data.has("revision")).isFalse();
    }

    @Test
    @DisplayName("빈 완성본도 PUBLISHED로 응답하고 UTC 입력 고정 시점을 보존한다")
    void returnsPublishedEmptySnapshot() throws SQLException {
        String snapshotId;
        try (var connection = database.connect()) {
            snapshotId = completed(connection, WEEK, 1, List.of());
            publish(connection, WEEK, 1, snapshotId);
        }

        var data = success(WEEK_QUERY);

        assertThat(field(data, "status")).isEqualTo("PUBLISHED");
        assertThat(field(data, "snapshotId")).isEqualTo(snapshotId);
        assertThat(field(data, "frozenAt")).isEqualTo("2026-08-24T00:01:00.123456Z");
        assertThat(data.path("items")).isEmpty();
        assertThat(data.has("revision")).isFalse();
    }

    @Test
    @DisplayName("현재 상품·브랜드 공개 조건을 limit 전에 적용하고 표시 정보와 저장한 순위·점수를 반환한다")
    void filtersBeforeLimitAndPreservesStoredRank() throws SQLException {
        try (var connection = database.connect()) {
            brand(connection, 11, false);
            brand(connection, 12, true);
            product(connection, 7, 11, false);
            product(connection, 9, 11, true);
            product(connection, 8, 11, false);
            product(connection, 10, 12, false);
            product(connection, 11, 99, false);
            String snapshotId = completed(connection, WEEK, 1, List.of(7L, 9L, 8L, 10L, 11L, 12L));
            publish(connection, WEEK, 1, snapshotId);
            execute(connection, "UPDATE products SET name='현재 상품명' WHERE id=8");
            execute(connection, "UPDATE brands SET name='현재 브랜드명' WHERE id=11");
        }

        var items = success(WEEK_QUERY + "&limit=2").path("items");

        assertThat(values(items, "rank")).containsExactly(1L, 3L);
        assertThat(values(items, "productId")).containsExactly(7L, 8L);
        assertThat(values(items, "score")).containsExactly(30L, 28L);
        assertThat(field(items.get(1), "name")).isEqualTo("현재 상품명");
        assertThat(items.get(1).path("price").asLong()).isEqualTo(1_000L);
        assertThat(items.get(1).path("likeCount").asLong()).isEqualTo(4L);
        assertThat(items.get(1).path("brand").path("brandId").asLong()).isEqualTo(11L);
        assertThat(field(items.get(1).path("brand"), "name")).isEqualTo("현재 브랜드명");
        assertThat(values(success(WEEK_QUERY + "&limit=100").path("items"), "productId")).containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("모든 상품이 현재 비공개여도 공개 snapshot 정보와 빈 목록을 응답한다")
    void keepsPublicationWhenAllItemsAreHidden() throws SQLException {
        String snapshotId;
        try (var connection = database.connect()) {
            brand(connection, 11, true);
            product(connection, 7, 11, false);
            snapshotId = completed(connection, WEEK, 1, List.of(7L));
            publish(connection, WEEK, 1, snapshotId);
        }

        var data = success(WEEK_QUERY);

        assertThat(field(data, "status")).isEqualTo("PUBLISHED");
        assertThat(field(data, "snapshotId")).isEqualTo(snapshotId);
        assertThat(data.path("items")).isEmpty();
    }

    @Test
    @DisplayName("limit 생략 시 10개를 반환하고 1과 100을 허용한다")
    void appliesDefaultAndAllowsInclusiveBounds() throws SQLException {
        List<Long> productIds = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
        try (var connection = database.connect()) {
            brand(connection, 11, false);
            for (long productId : productIds) {
                product(connection, productId, 11, false);
            }
            publish(connection, WEEK, 1, completed(connection, WEEK, 1, productIds));
        }

        assertThat(values(success(WEEK_QUERY).path("items"), "productId")).containsExactlyElementsOf(productIds.subList(0, 10));
        assertThat(values(success(WEEK_QUERY + "&limit=1").path("items"), "productId")).containsExactly(1L);
        assertThat(values(success(WEEK_QUERY + "&limit=100").path("items"), "productId")).containsExactlyElementsOf(productIds);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "?periodStart=", "?periodStart=2026-8-17", "?periodStart=2026-02-30", "?periodStart=2026-08-18",
        "?periodStart=2026-08-17T00:00:00Z", "?periodStart=%2B10000-01-03", "?periodStart=9999-12-27", WEEK_QUERY + "&limit=",
        WEEK_QUERY + "&limit=%20", WEEK_QUERY + "&limit=0", WEEK_QUERY + "&limit=-1", WEEK_QUERY + "&limit=101",
        WEEK_QUERY + "&limit=1.5", WEEK_QUERY + "&limit=2147483648", WEEK_QUERY + "&limit=abc"
    })
    @DisplayName("잘못된 요청을 공통 400 응답으로 반환한다")
    void rejectsInvalidParameters(String query) {
        var response = get(query);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(field(response.getBody().path("meta"), "result")).isEqualTo("FAIL");
        assertThat(field(response.getBody().path("meta"), "errorCode")).isEqualTo("Bad Request");
        assertThat(response.getBody().has("data")).isFalse();
    }

    @Test
    @DisplayName("헤더 조회 뒤 새 공개와 상품 삭제가 commit돼도 진행 중 응답은 같은 DB 읽기 시점을 유지한다")
    void readsFromSameSnapshotAcrossPublicationAndVisibilityChanges() throws SQLException {
        String first;
        String second;
        try (var connection = database.connect()) {
            brand(connection, 11, false);
            product(connection, 7, 11, false);
            product(connection, 8, 11, false);
            first = completed(connection, WEEK, 1, List.of(7L));
            second = completed(connection, WEEK, 2, List.of(8L));
            publish(connection, WEEK, 1, first);
        }
        BEFORE_ITEM_QUERY.set(() -> publishAndHideOldProduct(second));

        var inFlight = success(WEEK_QUERY);
        var subsequent = success(WEEK_QUERY);

        assertThat(BEFORE_ITEM_QUERY.get()).isNull();
        assertThat(field(inFlight, "snapshotId")).isEqualTo(first);
        assertThat(values(inFlight.path("items"), "productId")).containsExactly(7L);
        assertThat(field(subsequent, "snapshotId")).isEqualTo(second);
        assertThat(values(subsequent.path("items"), "productId")).containsExactly(8L);
    }

    private void publishAndHideOldProduct(String snapshotId) {
        try (var connection = database.connect()) {
            connection.setAutoCommit(false);
            execute(connection, "UPDATE weekly_ranking_publications SET snapshot_id=?, revision=2 WHERE period_start=?",
                snapshotId, WEEK);
            execute(connection, "UPDATE products SET deleted=1 WHERE id=7");
            connection.commit();
        } catch (SQLException failure) {
            throw new AssertionError("공개 전환과 상품 삭제 fixture를 commit할 수 없습니다.", failure);
        }
    }

    private JsonNode success(String query) {
        var response = get(query);
        assertThat(response.getStatusCode()).as("응답 %s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(field(response.getBody().path("meta"), "result")).isEqualTo("SUCCESS");
        var data = response.getBody().path("data");
        assertThat(data.path("items").isArray()).isTrue();
        return data;
    }

    private ResponseEntity<JsonNode> get(String query) {
        return http.getForEntity(URI.create(endpoint.toString() + query), JsonNode.class);
    }

    private static String field(JsonNode node, String name) {
        return node.path(name).asText();
    }

    private static List<Long> values(JsonNode items, String name) {
        return StreamSupport.stream(items.spliterator(), false).map(item -> item.path(name).asLong()).toList();
    }

    private static String completed(Connection connection, String periodStart, int revision, List<Long> productIds) throws SQLException {
        String snapshotId = UUID.randomUUID().toString();
        execute(connection, """
            INSERT INTO weekly_ranking_snapshots
                (snapshot_id,period_start,period_end,revision,state,input_contract_version,frozen_at,input_count,input_checksum)
            VALUES (?,?,DATE_ADD(?, INTERVAL 7 DAY),?,'FROZEN',1,'2026-08-24 00:01:00.123456',?,REPEAT('a',64))
            """, snapshotId, periodStart, periodStart, revision, productIds.size());
        for (int index = 0; index < productIds.size(); index++) {
            execute(connection, "INSERT INTO weekly_rankings (snapshot_id,product_id,score,ranking_position) VALUES (?,?,?,?)",
                snapshotId, productIds.get(index), 30 - index, index + 1);
        }
        execute(connection, """
            INSERT INTO weekly_ranking_completions (snapshot_id,result_contract_version,product_count,result_checksum,completed_at)
            VALUES (?,1,?,REPEAT('b',64),'2026-08-24 00:02:00.123456')
            """, snapshotId, productIds.size());
        return snapshotId;
    }

    private static void publish(Connection connection, String periodStart, int revision, String snapshotId) throws SQLException {
        execute(connection, "INSERT INTO weekly_ranking_publications (period_start,snapshot_id,revision) VALUES (?,?,?)",
            periodStart, snapshotId, revision);
    }

    private static void brand(Connection connection, long id, boolean deleted) throws SQLException {
        execute(connection, "INSERT INTO brands (id,deleted,created_at,updated_at,name) VALUES (?,?,NOW(6),NOW(6),'브랜드')",
            id, deleted);
    }

    private static void product(Connection connection, long id, long brandId, boolean deleted) throws SQLException {
        execute(connection, """
            INSERT INTO products (id,brand_id,deleted,created_at,updated_at,name,price,stock,like_count)
            VALUES (?,?,?,NOW(6),NOW(6),'상품',1000,0,4)
            """, id, brandId, deleted);
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("weekly-ranking-api-test")
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
        "com.loopers.config.jpa", "com.loopers.config.jackson", "com.loopers.interfaces.api.ranking",
        "com.loopers.infrastructure.query.ranking"
    })
    @Import({ApiControllerAdvice.class, ApiErrorMapper.class})
    static class RankingConfiguration {
        @Bean
        HibernatePropertiesCustomizer rankingStatementInspector() {
            return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR,
                (StatementInspector) sql -> {
                    if (sql.contains("from weekly_rankings ")) {
                        var beforeQuery = BEFORE_ITEM_QUERY.getAndSet(null);
                        if (beforeQuery != null) {
                            beforeQuery.run();
                        }
                    }
                    return sql;
                });
        }
    }
}
