package com.loopers.interfaces.api.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.ranking.WeeklyRankingQueryService;
import com.loopers.application.ranking.WeeklyRankingReader;
import com.loopers.config.AdminBoundaryConfig;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.ranking.VisibleRankingProducts;
import com.loopers.infrastructure.brand.BrandNameConverter;
import com.loopers.infrastructure.ranking.JdbcPublishedRankingRepository;
import com.loopers.infrastructure.ranking.JdbcVisibleRankingProducts;
import com.loopers.infrastructure.ranking.RankingQueryConfiguration;
import com.loopers.infrastructure.ranking.RankingReadResource;
import com.loopers.interfaces.api.commerce.CommerceErrors;
import com.loopers.interfaces.api.commerce.CommerceWebConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, independently owned ranking/main MySQL stores; never loads the shared test datasource fixture. */
@Testcontainers
@Timeout(45)
class WeeklyRankingHttpIntegrationTest {
    private static final LocalDate WEEK = LocalDate.parse("2026-08-17");
    private static final Instant FROZEN_AT = Instant.parse("2026-08-24T00:05:00.123456789Z");
    @Container private static final MySQLContainer<?> MAIN = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w9_ranking_main_owned");
    @Container private static final MySQLContainer<?> RANKING = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w9_ranking_read_owned");
    private static ConfigurableApplicationContext context;
    private static HikariDataSource mainWriter;
    private static HikariDataSource rankingWriter;
    private static JdbcTemplate main;
    private static JdbcTemplate ranking;
    private static TrackingProducts products;
    private static ObjectMapper mapper;
    private static HttpClient http;

    @BeforeAll
    static void startOnlyOwnedStoresAndHttp() {
        try (var mainSource = pool(MAIN)) {
            new ResourceDatabasePopulator(new ClassPathResource("db/schema/001-brand.sql"),
                new ClassPathResource("db/schema/003-product.sql")).execute(mainSource);
        }
        rankingWriter = pool(RANKING);
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/ranking/001-weekly-ranking-input.sql"),
            new ClassPathResource("fixtures/ranking/002-weekly-ranking-score.sql"),
            new ClassPathResource("fixtures/ranking/003-weekly-ranking-publication.sql")).execute(rankingWriter);
        ranking = new JdbcTemplate(rankingWriter);
        context = boot(true);
        mainWriter = pool(MAIN);
        main = new JdbcTemplate(mainWriter);
        products = context.getBean(TrackingProducts.class);
        mapper = context.getBean(ObjectMapper.class);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterAll
    static void closeOnlyOwnedResources() {
        if (http != null) { http.close(); }
        if (context != null) { context.close(); }
        if (mainWriter != null) { mainWriter.close(); }
        if (rankingWriter != null) { rankingWriter.close(); }
    }

    @BeforeEach
    void resetOnlyThisTestsRows() {
        products.reset();
        ranking.update("DELETE FROM weekly_ranking_publication");
        ranking.update("DELETE FROM weekly_ranking_result");
        ranking.update("DELETE FROM weekly_ranking_snapshot");
        main.update("DELETE FROM product");
        main.update("DELETE FROM brand");
        main.update("INSERT INTO brand VALUES (1,'공개 브랜드',NOW(6),NOW(6),NULL)");
        main.update("INSERT INTO brand VALUES (2,'삭제 브랜드',NOW(6),NOW(6),NOW(6))");
    }

    @Test
    void realHttpUsesPublishedScoresCurrentNamesAndBothDeletionFiltersBeforeLimit() throws Exception {
        publish(1, List.of(new Entry(7, 5, 1), new Entry(9, 5, 2), new Entry(8, 1, 3)));
        product(7, 1, "삭제 상품", 10, true);
        product(9, 2, "삭제 브랜드의 상품", 10, false);
        product(8, 1, "현재 이름이며 품절", 0, false);

        var body = success(get("?periodStart=2026-08-17&limit=1"));

        assertThat(body.path("status").textValue()).isEqualTo("PUBLISHED");
        assertThat(body.path("periodStart").textValue()).isEqualTo("2026-08-17");
        assertThat(body.path("periodEnd").textValue()).isEqualTo("2026-08-24");
        assertThat(body.path("snapshotId").textValue()).isEqualTo(id(1));
        assertThat(body.path("revision").intValue()).isOne();
        assertThat(body.path("frozenAt").textValue()).isEqualTo(FROZEN_AT.toString());
        assertThat(body.path("items")).hasSize(1);
        assertItem(body.at("/items/0"), 8, "현재 이름이며 품절", 1, 3);
        assertThat(ranking.queryForObject("SELECT COUNT(*) FROM weekly_ranking_result", Long.class)).isEqualTo(3);
    }

    @Test
    void cursorPagesStayOnOnePublicationAndOneMainDbReadViewWhileBothStoresChange() throws Exception {
        List<Entry> old = new ArrayList<>();
        for (int index = 1; index <= 130; index++) {
            old.add(new Entry(index, 131 - index, index));
            product(index, 1, "원래 이름 " + index, 0, index <= 128);
        }
        publish(1, old);
        complete(2, List.of(new Entry(130, 999, 1)));
        products.afterFirstRead = () -> {
            ranking.update("UPDATE weekly_ranking_publication SET snapshot_id=?,revision=2 WHERE period_start=?", id(2), WEEK);
            main.update("UPDATE product SET name='변경된 이름',deleted_at=NOW(6) WHERE id=129");
        };

        var first = success(get("?periodStart=2026-08-17&limit=2"));

        assertThat(first.path("snapshotId").textValue()).isEqualTo(id(1));
        assertThat(first.path("items")).hasSize(2);
        assertItem(first.at("/items/0"), 129, "원래 이름 129", 2, 129);
        assertItem(first.at("/items/1"), 130, "원래 이름 130", 1, 130);
        assertThat(products.calls.get()).isEqualTo(3);
        assertThat(products.connections).hasSize(3).containsOnly(products.connections.getFirst());
        var next = success(get("?periodStart=2026-08-17"));
        assertThat(next.path("snapshotId").textValue()).isEqualTo(id(2));
        assertItem(next.at("/items/0"), 130, "원래 이름 130", 999, 1);
    }

    @Test
    void unpublishedEmptyPublishedAndHiddenPublishedAreDifferentOutcomes() throws Exception {
        assertError(get("?periodStart=2026-08-17"), 404, "RANKING_NOT_PUBLISHED");
        publish(1, List.of());
        assertThat(success(get("?periodStart=2026-08-17")).path("items")).isEmpty();
        publish(2, List.of(new Entry(1, 7, 1)));
        product(1, 1, "삭제됨", 2, true);
        var hidden = success(get("?periodStart=2026-08-17"));
        assertThat(hidden.path("snapshotId").textValue()).isEqualTo(id(2));
        assertThat(hidden.path("items")).isEmpty();
    }

    @Test
    void defaultAndMaximumLimitApplyAfterVisibilityWithoutChangingStoredRanks() throws Exception {
        List<Entry> rows = new ArrayList<>();
        for (int index = 1; index <= 105; index++) {
            rows.add(new Entry(index, 1, index));
            product(index, 1, "상품 " + index, 1, false);
        }
        publish(1, rows);
        assertThat(success(get("?periodStart=2026-08-17")).path("items")).hasSize(10);
        var all = success(get("?periodStart=2026-08-17&limit=100"));
        assertThat(all.path("items")).hasSize(100);
        assertThat(all.at("/items/99/rank").longValue()).isEqualTo(100);
    }

    @Test
    void malformedHttpInputUsesTheExistingCommerceEnvelope() throws Exception {
        for (String query : List.of("", "?periodStart=abc", "?periodStart=2026-08-18", "?periodStart=2026-02-30",
            "?periodStart=2026-08-17&limit=0", "?periodStart=2026-08-17&limit=101",
            "?periodStart=2026-08-17&limit=abc", "?periodStart=2026-08-17&limit=2147483648")) {
            assertError(get(query), 400, "INVALID_REQUEST");
        }
        assertThat(products.calls).hasValue(0);
    }

    @Test
    void missingCompletedResultIsInternalErrorWithoutServingStageOrAnotherRevision() throws Exception {
        publish(1, List.of(new Entry(1, 1, 1)));
        product(1, 1, "상품", 1, false);
        ranking.update("DELETE FROM weekly_ranking_result WHERE snapshot_id=?", id(1));
        assertError(get("?periodStart=2026-08-17"), 500, "INTERNAL_ERROR");
        assertThat(products.calls).hasValue(0);
    }

    @Test
    void aPointerToAnUnfinishedManifestCannotExposeStage() throws Exception {
        publish(1, List.of(new Entry(1, 1, 1)));
        ranking.update("""
            UPDATE weekly_ranking_snapshot SET calculation_state='CALCULATING',result_count=NULL,
                result_checksum_version=NULL,result_checksum=NULL WHERE snapshot_id=?
            """, id(1));
        assertError(get("?periodStart=2026-08-17"), 500, "INTERNAL_ERROR");
        assertThat(products.calls).hasValue(0);
    }

    @Test
    void defaultOffDoesNotCreateARankingPoolOrFallBackToTheMainDatabase() throws Exception {
        try (var disabled = boot(false)) {
            assertError(get(disabled, "?periodStart=2026-08-17"), 503, "RANKING_QUERY_UNAVAILABLE");
            assertThat(disabled.getBeansOfType(DataSource.class)).hasSize(1).containsKey("mySqlMainDataSource");
        }
    }

    @Test
    void realRankingConnectionFailureReturns503AndRecoversWithoutAnotherSnapshotFallback() throws Exception {
        publish(1, List.of(new Entry(1, 1, 1)));
        product(1, 1, "상품", 0, false);
        success(get("?periodStart=2026-08-17"));
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(RANKING.getContainerId()).exec();
        try {
            assertError(get("?periodStart=2026-08-17"), 503, "RANKING_QUERY_UNAVAILABLE");
        } finally {
            docker.unpauseContainerCmd(RANKING.getContainerId()).exec();
        }
        assertThat(success(get("?periodStart=2026-08-17")).path("snapshotId").textValue()).isEqualTo(id(1));
    }

    @Test
    void realMainDbConnectionFailureAlsoReturns503AndDoesNotLeakPartialRankings() throws Exception {
        publish(1, List.of(new Entry(1, 1, 1)));
        product(1, 1, "상품", 0, false);
        success(get("?periodStart=2026-08-17"));
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(MAIN.getContainerId()).exec();
        try {
            assertError(get("?periodStart=2026-08-17"), 503, "RANKING_QUERY_UNAVAILABLE");
        } finally {
            docker.unpauseContainerCmd(MAIN.getContainerId()).exec();
        }
        assertThat(success(get("?periodStart=2026-08-17")).path("items")).hasSize(1);
    }

    @Test
    void rankingPoolDoesNotChangeMainJpaTransactionManagerOrAutoConfiguredJdbcTemplate() {
        var source = context.getBean("mySqlMainDataSource", DataSource.class);
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        assertThat(context.getBean(JpaTransactionManager.class).getDataSource()).isSameAs(source);
        assertThat(context.getBean(JdbcTemplate.class).getDataSource()).isSameAs(source);
        assertThat(context.getBean(RankingReadResource.class).jdbc().getDataSource()).isNotSameAs(source);
    }

    private static ConfigurableApplicationContext boot(boolean enabled) {
        List<String> arguments = new ArrayList<>(List.of(
            "--spring.config.name=w9-isolated-ranking-http", "--spring.profiles.active=test", "--server.port=0",
            "--server.address=127.0.0.1", "--spring.main.banner-mode=off", "--spring.jpa.hibernate.ddl-auto=none",
            "--spring.jpa.open-in-view=false", "--spring.jpa.show-sql=false", "--management.endpoints.enabled-by-default=false",
            "--spring.jackson.default-property-inclusion=non_null", "--spring.jackson.serialization.write-dates-as-timestamps=false",
            "--commerce.ranking.query.jdbc-url=" + RANKING.getJdbcUrl(),
            "--commerce.ranking.query.username=" + RANKING.getUsername(), "--commerce.ranking.query.password=" + RANKING.getPassword()));
        if (enabled) {
            arguments.add("--commerce.ranking.query.enabled=true");
        }
        return new SpringApplicationBuilder(IsolatedApplication.class).web(WebApplicationType.SERVLET)
            .run(arguments.toArray(String[]::new));
    }

    private static HikariDataSource pool(MySQLContainer<?> container) {
        var pool = new HikariDataSource();
        pool.setJdbcUrl(container.getJdbcUrl());
        pool.setUsername(container.getUsername());
        pool.setPassword(container.getPassword());
        pool.setMaximumPoolSize(3);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(1000);
        pool.setValidationTimeout(500);
        pool.addDataSourceProperty("connectTimeout", "1000");
        pool.addDataSourceProperty("socketTimeout", "2000");
        return pool;
    }

    private void product(long id, long brandId, String name, int stock, boolean deleted) {
        main.update("""
            INSERT INTO product(id,brand_id,name,price,stock_quantity,created_at,updated_at,deleted_at)
            VALUES(?,?,?,1000,?,NOW(6),NOW(6),?)
            """, id, brandId, name, stock, deleted ? "2026-08-25 00:00:00" : null);
    }

    private void publish(int revision, List<Entry> entries) throws Exception {
        complete(revision, entries);
        ranking.update("""
            INSERT INTO weekly_ranking_publication(period_start,snapshot_id,revision) VALUES(?,?,?)
            ON DUPLICATE KEY UPDATE snapshot_id=VALUES(snapshot_id),revision=VALUES(revision)
            """, WEEK, id(revision), revision);
    }

    private void complete(int revision, List<Entry> entries) throws Exception {
        String canonical = entries.stream().map(row -> row.productId + "\t" + row.score + "\t" + row.rank + "\n")
            .reduce("", String::concat);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        ranking.update("""
            INSERT INTO weekly_ranking_snapshot(snapshot_id,period_start,period_end,revision,frozen_at_second,
                frozen_at_nano,input_count,product_count,checksum_version,input_checksum,input_state,
                calculation_state,result_count,result_checksum_version,result_checksum)
            VALUES(?,?,?,?,?,?,?,?,'v1',?,'FROZEN','COMPLETE',?,'v1',?)
            """, id(revision), WEEK, WEEK.plusDays(7), revision, FROZEN_AT.getEpochSecond(), FROZEN_AT.getNano(),
            entries.size(), entries.size(), "0".repeat(64), entries.size(), checksum);
        for (Entry entry : entries) {
            ranking.update("INSERT INTO weekly_ranking_result VALUES(?,?,?,?)", id(revision),
                entry.productId, entry.score, entry.rank);
        }
    }

    private static String id(int revision) { return "weekly-20260817-r" + revision; }

    private HttpResponse<String> get(String query) throws Exception { return get(context, query); }

    private HttpResponse<String> get(ConfigurableApplicationContext target, String query) throws Exception {
        int port = ((ServletWebServerApplicationContext) target).getWebServer().getPort();
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/rankings/weekly" + query))
            .timeout(Duration.ofSeconds(12)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode success(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var json = mapper.readTree(response.body());
        assertThat(json.at("/meta/result").textValue()).isEqualTo("SUCCESS");
        assertThat(json.at("/meta").has("errorCode")).isFalse();
        return json.path("data");
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var json = mapper.readTree(response.body());
        assertThat(json.at("/meta/result").textValue()).isEqualTo("FAIL");
        assertThat(json.at("/meta/errorCode").textValue()).isEqualTo(code);
        assertThat(json.has("data")).isFalse();
    }

    private void assertItem(JsonNode item, long id, String name, long score, long rank) {
        assertThat(item.path("productId").longValue()).isEqualTo(id);
        assertThat(item.path("productName").textValue()).isEqualTo(name);
        assertThat(item.path("score").longValue()).isEqualTo(score);
        assertThat(item.path("rank").longValue()).isEqualTo(rank);
    }

    private record Entry(long productId, long score, long rank) { }

    static final class TrackingProducts implements VisibleRankingProducts {
        private final JdbcVisibleRankingProducts delegate;
        private final DataSource source;
        final AtomicInteger calls = new AtomicInteger();
        final List<Connection> connections = new ArrayList<>();
        volatile Runnable afterFirstRead = () -> { };

        TrackingProducts(JdbcVisibleRankingProducts delegate, DataSource source) {
            this.delegate = delegate;
            this.source = source;
        }

        void reset() { calls.set(0); connections.clear(); afterFirstRead = () -> { }; }

        @Override
        public Map<Long, String> names(List<Long> ids) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            Connection connection = DataSourceUtils.getConnection(source);
            connections.add(connection);
            try {
                var result = delegate.names(ids);
                if (calls.incrementAndGet() == 1) {
                    afterFirstRead.run();
                }
                return result;
            } finally {
                DataSourceUtils.releaseConnection(connection, source);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {KafkaAutoConfiguration.class, RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
    @EntityScan(basePackageClasses = {Brand.class, Product.class, BrandNameConverter.class})
    @Import({RankingQueryConfiguration.class, JdbcPublishedRankingRepository.class, JdbcVisibleRankingProducts.class,
        WeeklyRankingQueryService.class, WeeklyRankingReader.class, WeeklyRankingController.class,
        CommerceErrors.class, CommerceWebConfiguration.class, AdminBoundaryConfig.class})
    static class IsolatedApplication {
        @Primary
        @Bean(name = "mySqlMainDataSource", destroyMethod = "close")
        HikariDataSource mainDataSource() { return pool(MAIN); }

        @Primary
        @Bean
        TrackingProducts products(JdbcVisibleRankingProducts delegate, DataSource source) {
            return new TrackingProducts(delegate, source);
        }
    }
}
