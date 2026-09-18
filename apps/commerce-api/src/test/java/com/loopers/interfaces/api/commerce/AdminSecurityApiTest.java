package com.loopers.interfaces.api.commerce;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminSecurityApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private OrderRepository orders;
    @Autowired private UserRepository users;
    @Autowired private FixtureUserInitializer fixture;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private long brandId;
    private long emptyBrandId;
    private long productId;
    private long orderId;

    @BeforeEach
    void prepare() {
        fixture.initialize();
        Brand brand = brands.save(new Brand("브랜드"));
        brandId = brand.getId();
        emptyBrandId = brands.save(new Brand("빈 브랜드")).getId();
        Product product = products.save(new Product(brand, "상품", 1000, 5));
        productId = product.getId();
        orderId = orders.save(Order.create(users.findById(1).orElseThrow(),
            List.of(new OrderItem(product, 1)))).getId();
    }

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @ParameterizedTest(name = "ADMIN permits {0}")
    @MethodSource("endpoints")
    void adminAuthorityAllowsEveryAdministratorEndpointWithoutFixtureHeader(Endpoint endpoint) throws Exception {
        mvc.perform(input(endpoint).with(user("administrator-outside-fixture").roles("ADMIN")))
            .andExpect(status().is(endpoint.successStatus()))
            .andExpect(jsonPath("$.meta.result").value("SUCCESS"));
    }

    @ParameterizedTest(name = "USER rejects {0}")
    @MethodSource("endpoints")
    void normalUserIsRejectedWithValidCsrfAndNoDatabaseChange(Endpoint endpoint) throws Exception {
        var before = databaseState();
        mvc.perform(input(endpoint).with(user("customer").roles("USER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.meta.errorCode").value("FORBIDDEN"));
        assertThat(databaseState()).isEqualTo(before);
    }

    @ParameterizedTest(name = "anonymous rejects {0}")
    @MethodSource("endpoints")
    void anonymousIsRejectedWithValidCsrfAndNoDatabaseChange(Endpoint endpoint) throws Exception {
        var before = databaseState();
        mvc.perform(input(endpoint))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.meta.errorCode").value("FORBIDDEN"));
        assertThat(databaseState()).isEqualTo(before);
    }

    @ParameterizedTest(name = "missing CSRF rejects {0}")
    @MethodSource("mutations")
    void evenAdminCannotWriteWithoutCsrf(Endpoint endpoint) throws Exception {
        var before = databaseState();
        mvc.perform(rawInput(endpoint).with(user("admin").roles("ADMIN")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.meta.errorCode").value("FORBIDDEN"));
        assertThat(databaseState()).isEqualTo(before);
    }

    @ParameterizedTest(name = "invalid CSRF rejects {0}")
    @MethodSource("mutations")
    void evenAdminCannotWriteWithInvalidCsrf(Endpoint endpoint) throws Exception {
        var before = databaseState();
        mvc.perform(rawInput(endpoint).with(user("admin").roles("ADMIN")).with(csrf().useInvalidToken()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.meta.errorCode").value("FORBIDDEN"));
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test
    void administratorHeaderCannotAuthenticateOrElevateNormalUser() throws Exception {
        var before = databaseState();
        mvc.perform(get("/api-admin/v1/brands").header("X-USER-ID", "admin"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api-admin/v1/brands").header("X-USER-ID", "admin")
                .with(user("admin").roles("USER")))
            .andExpect(status().isForbidden());
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test
    void authenticatedAdminRoleIgnoresCustomerAndUnknownHeaders() throws Exception {
        mvc.perform(get("/api-admin/v1/brands").header("X-USER-ID", "alice")
                .with(user("operator").roles("ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(get("/api-admin/v1/brands").header("X-USER-ID", "unknown")
                .with(user("operator").roles("ADMIN")))
            .andExpect(status().isOk());
    }

    @Test
    void authorizationRunsBeforePathAndBodyParsing() throws Exception {
        var before = databaseState();
        mvc.perform(get("/api-admin/v1/brands/abc").with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api-admin/v1/brands").contentType(MediaType.APPLICATION_JSON)
                .content("{bad").with(csrf()).with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api-admin/v1/brands").contentType(MediaType.APPLICATION_JSON)
                .content("{bad").with(csrf()))
            .andExpect(status().isForbidden());
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test
    void securityBoundaryDoesNotRequireCsrfForCustomerFixtureRequests() throws Exception {
        mvc.perform(post("/api/v1/points/charge").header("X-USER-ID", "alice")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(10000));
        mvc.perform(get("/api/v1/brands/" + brandId)).andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder input(Endpoint endpoint) {
        MockHttpServletRequestBuilder input = rawInput(endpoint);
        return endpoint.writes() ? input.with(csrf()) : input;
    }

    private MockHttpServletRequestBuilder rawInput(Endpoint endpoint) {
        String path = endpoint.path().replace("{brand}", String.valueOf(brandId))
            .replace("{emptyBrand}", String.valueOf(emptyBrandId))
            .replace("{product}", String.valueOf(productId)).replace("{order}", String.valueOf(orderId));
        MockHttpServletRequestBuilder input = request(endpoint.method(), path);
        if (endpoint.body() != null) {
            input.contentType(MediaType.APPLICATION_JSON)
                .content(endpoint.body().replace("{brand}", String.valueOf(brandId)));
        }
        return input;
    }

    private Map<String, List<Map<String, Object>>> databaseState() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("user", "brand", "product", "like", "order", "order_item")) {
            result.put(table, jdbc.queryForList("SELECT * FROM `" + table + "` ORDER BY id"));
        }
        return result;
    }

    private static Stream<Endpoint> mutations() {
        return endpoints().filter(Endpoint::writes);
    }

    private static Stream<Endpoint> endpoints() {
        return Stream.of(
            new Endpoint(HttpMethod.GET, "/api-admin/v1/brands", null, 200),
            new Endpoint(HttpMethod.POST, "/api-admin/v1/brands", "{\"name\":\"새 브랜드\"}", 201),
            new Endpoint(HttpMethod.GET, "/api-admin/v1/brands/{brand}", null, 200),
            new Endpoint(HttpMethod.PUT, "/api-admin/v1/brands/{brand}", "{\"name\":\"수정 브랜드\"}", 200),
            new Endpoint(HttpMethod.DELETE, "/api-admin/v1/brands/{emptyBrand}", null, 200),
            new Endpoint(HttpMethod.GET, "/api-admin/v1/products", null, 200),
            new Endpoint(HttpMethod.POST, "/api-admin/v1/products",
                "{\"brandId\":{brand},\"name\":\"새 상품\",\"price\":2000,\"stockQuantity\":2}", 201),
            new Endpoint(HttpMethod.GET, "/api-admin/v1/products/{product}", null, 200),
            new Endpoint(HttpMethod.PUT, "/api-admin/v1/products/{product}", "{\"name\":\"수정 상품\",\"price\":2000}", 200),
            new Endpoint(HttpMethod.DELETE, "/api-admin/v1/products/{product}", null, 200),
            new Endpoint(HttpMethod.PUT, "/api-admin/v1/products/{product}/stock", "{\"stockQuantity\":3}", 200),
            new Endpoint(HttpMethod.GET, "/api-admin/v1/orders", null, 200),
            new Endpoint(HttpMethod.GET, "/api-admin/v1/orders/{order}", null, 200)
        );
    }

    private record Endpoint(HttpMethod method, String path, String body, int successStatus) {
        boolean writes() {
            return method != HttpMethod.GET;
        }
    }
}
