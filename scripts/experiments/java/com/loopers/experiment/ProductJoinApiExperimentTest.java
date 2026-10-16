package com.loopers.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.BrandInfo;
import com.loopers.application.product.CustomerProductInfo;
import com.loopers.application.product.ProductPage;
import com.loopers.application.product.ProductQueryService;
import com.loopers.application.product.PricePageReader;
import com.loopers.application.product.ProductSort;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.PopularPageCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "spring.jpa.hibernate.ddl-auto=validate",
        "datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3307/loopers_benchmark",
        "datasource.mysql-jpa.main.username=application", "datasource.mysql-jpa.main.password=application"})
@ActiveProfiles("benchmark")
@Import(ProductJoinApiExperimentTest.Config.class)
class ProductJoinApiExperimentTest {
    private static final AtomicBoolean JOIN = new AtomicBoolean();
    private static final String PAGE = "/api/v1/products?brandId=7&page=0&size=20&sort=price_asc";
    private static final String JOIN_SQL = """
        SELECT pg.id,pg.name,pg.price,pg.created_at,pg.brand_id,pg.brand_name,
               pg.brand_description,COUNT(l.id) AS like_count
        FROM (
          SELECT p.id,p.name,p.price,p.created_at,b.id AS brand_id,
                 b.name AS brand_name,b.description AS brand_description
          FROM product p JOIN brand b ON b.id=p.brand_id
          WHERE p.deleted_at IS NULL AND b.deleted_at IS NULL %s
          ORDER BY p.price ASC,p.created_at DESC,p.id DESC LIMIT ? OFFSET ?
        ) pg
        LEFT JOIN product_like l ON l.product_id=pg.id
        GROUP BY pg.id,pg.name,pg.price,pg.created_at,pg.brand_id,pg.brand_name,pg.brand_description
        ORDER BY pg.price ASC,pg.created_at DESC,pg.id DESC
        """;

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void compareSameHttpApiWithSplitAndPageFirstJoin() throws Exception {
        Path root = Path.of(System.getProperty("week4.repoRoot"));
        Path output = root.resolve("docs/week4/measurements/join-api-final.json");
        assertThat(Files.exists(output)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from product", Long.class)).isEqualTo(10_000L);
        assertThat(jdbc.queryForObject("select count(*) from product_like", Long.class)).isEqualTo(62_756L);
        JsonNode expected = mapper.readTree(python(root, """
            import importlib.util,json,sys
            sys.dont_write_bytecode=True
            s=importlib.util.spec_from_file_location('p','scripts/measure-week4-query.py')
            m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
            print(json.dumps(m.expected_response()))
            """));
        Map<String, List<Double>> times = new LinkedHashMap<>();
        times.put("split", new ArrayList<>());
        times.put("join", new ArrayList<>());
        for (int round = 0; round < 40; round++) {
            for (boolean useJoin : (round % 2 == 0 ? new boolean[]{false, true} : new boolean[]{true, false})) {
                JOIN.set(useJoin);
                long start = System.nanoTime();
                HttpResponse<String> response = request(PAGE);
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode body = mapper.readTree(response.body());
                assertThat(body.path("meta").path("result").asText()).isEqualTo("SUCCESS");
                assertThat(body.path("data")).isEqualTo(expected);
                if (round >= 10) {
                    times.get(useJoin ? "join" : "split").add(elapsed);
                }
            }
        }
        List<String> contractCases = List.of(
            "/api/v1/products?brandId=7&page=1&size=20&sort=price_asc",
            "/api/v1/products?brandId=7&page=5&size=20&sort=price_asc",
            "/api/v1/products?page=0&size=20&sort=price_asc",
            "/api/v1/products?brandId=99&sort=price_asc",
            "/api/v1/products?brandId=10001&sort=price_asc",
            "/api/v1/products?brandId=7&page=-1&sort=price_asc",
            "/api/v1/products?brandId=7&size=0&sort=price_asc",
            "/api/v1/products?brandId=7&page=2147483647&size=20&sort=price_asc",
            "/api/v1/products?brandId=7&sort=latest",
            "/api/v1/products?brandId=7&sort=likes_desc"
        );
        for (String url : contractCases) {
            JOIN.set(false);
            HttpResponse<String> split = request(url);
            JOIN.set(true);
            HttpResponse<String> join = request(url);
            assertThat(join.statusCode()).isEqualTo(split.statusCode());
            assertThat(mapper.readTree(join.body())).isEqualTo(mapper.readTree(split.body()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("notes", List.of("Experiment-only Spring configuration; production source unchanged.",
            "Same live HTTP server/client, alternating order, 10 warmup + 30 samples each.",
            "Persistent Java HttpClient; different protocol from earlier sequential urllib measurements.",
            "Both keep brand validation and the same page; JOIN also includes brand fields.",
            "Timing excludes JSON parsing/assertions and SQL capture.",
            "No cache, concurrent load or write performance benchmark."));
        result.put("fixed_response", expected);
        result.put("all_http_ms", times);
        result.put("http_ms", Map.of("split", stats(times.get("split")), "join", stats(times.get("join"))));
        result.put("contract_cases", contractCases);
        result.put("validated_fixed_requests", 80);
        String capture = """
            import importlib.util,json,sys
            sys.dont_write_bytecode=True
            s=importlib.util.spec_from_file_location('p','scripts/measure-week4-query.py')
            m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
            m.URL=sys.argv[1]
            print(json.dumps(m.capture_sql(m.expected_response())))
            """;
        JOIN.set(false);
        result.put("split_sql_probe", mapper.readTree(python(root, capture, "http://localhost:" + port + PAGE)));
        JOIN.set(true);
        result.put("join_sql_probe", mapper.readTree(python(root, capture, "http://localhost:" + port + PAGE)));
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        JOIN.set(false);
    }

    private HttpResponse<String> request(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Double> stats(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return Map.of("median", (sorted.get(14) + sorted.get(15)) / 2,
            "mean", values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
            "p95", sorted.get(28), "min", sorted.getFirst(), "max", sorted.getLast());
    }

    private static String python(Path root, String code, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("python3", "-c", code));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(root.toFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).withFailMessage(stderr).isZero();
        return stdout;
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ProductQueryService experimentQuery(BrandService brands, ProductService products, LikeService likes,
            JdbcTemplate jdbc, PricePageReader pricePages, PopularPageCache popularPages,
            @Qualifier("productQueryService") ProductQueryService split) {
            return new JoinedQuery(brands, products, likes, jdbc, split, pricePages, popularPages);
        }
    }

    static class JoinedQuery extends ProductQueryService {
        private final BrandService brands;
        private final ProductService products;
        private final JdbcTemplate jdbc;
        private final ProductQueryService split;

        JoinedQuery(BrandService brands, ProductService products, LikeService likes,
            JdbcTemplate jdbc, ProductQueryService split, PricePageReader pricePages, PopularPageCache popularPages) {
            super(brands, products, likes, pricePages, popularPages, false);
            this.brands = brands;
            this.products = products;
            this.jdbc = jdbc;
            this.split = split;
        }

        @Override
        @Transactional(readOnly = true)
        public ProductPage<CustomerProductInfo> getList(Long brandId, int page, int size, ProductSort sort) {
            if (!JOIN.get() || sort != ProductSort.PRICE_ASC || page < 0 || size < 1 || size > 100) {
                return split.getList(brandId, page, size, sort);
            }
            if (brandId != null) {
                brands.get(brandId);
            }
            long offset = (long) page * size;
            if (offset > Integer.MAX_VALUE) {
                return new ProductPage<>(List.of(), page, size);
            }
            List<Object> args = new ArrayList<>();
            if (brandId != null) {
                args.add(brandId);
            }
            args.add(size);
            args.add(offset);
            String sql = JOIN_SQL.formatted(brandId == null ? "" : "AND p.brand_id=?");
            List<CustomerProductInfo> items = jdbc.query(sql, (rs, row) -> new CustomerProductInfo(
                rs.getLong("id"), new BrandInfo(rs.getLong("brand_id"), rs.getString("brand_name"),
                    rs.getString("brand_description")), rs.getString("name"), rs.getLong("price"),
                rs.getLong("like_count"), rs.getTimestamp("created_at").toLocalDateTime().atZone(ZoneOffset.UTC)
            ), args.toArray());
            return new ProductPage<>(items, page, size);
        }
    }
}
