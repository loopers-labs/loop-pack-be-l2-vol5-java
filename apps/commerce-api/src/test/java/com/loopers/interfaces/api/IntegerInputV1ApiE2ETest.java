package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class IntegerInputV1ApiE2ETest {
    @Autowired private TestRestTemplate rest;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private OrderRepository orders;
    @Autowired private PointRepository points;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private RedisCleanUp redisCleanup;

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
        redisCleanup.truncateAll();
    }

    @ParameterizedTest
    @MethodSource("invalidProductInputs")
    void rejectsInvalidProductNumbersWithoutChangingData(String operation, String field, String value) throws Exception {
        Fixture fixture = fixture();
        String number = value.replace("ID", fixture.product().getBrandId().toString());
        String json = switch (operation) {
            case "create" -> "{\"brandId\":" + (field.equals("brandId") ? number : fixture.product().getBrandId())
                + ",\"name\":\"New\",\"price\":" + (field.equals("price") ? number : "100") + "}";
            case "update" -> "{\"name\":\"Changed\",\"price\":" + number + "}";
            case "stock" -> "{\"quantity\":" + number + "}";
            default -> throw new IllegalArgumentException(operation);
        };
        String path = "/api-admin/v1/products" + switch (operation) {
            case "create" -> "";
            case "update" -> "/" + fixture.product().getId();
            default -> "/" + fixture.product().getId() + "/stock";
        };

        MvcResult response = admin(operation.equals("create") ? HttpMethod.POST : HttpMethod.PUT, path, json);

        assertThat(response.getResponse().getStatus()).isEqualTo(400);
        assertThat(mapper.readTree(response.getResponse().getContentAsString()).at("/meta/result").asText()).isEqualTo("FAIL");
        assertUnchanged(fixture);
    }

    static Stream<Arguments> invalidProductInputs() {
        return Stream.of(
            Arguments.of("create", "brandId", "ID.9"),
            Arguments.of("create", "price", "100.9"),
            Arguments.of("update", "price", "100000000.9"),
            Arguments.of("stock", "quantity", "-0.9"),
            Arguments.of("stock", "quantity", "9223372036854775808.0"),
            Arguments.of("stock", "quantity", "null")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidOrderInputs")
    void rejectsInvalidOrderNumbersWithoutChangingData(String productId, String quantity) throws Exception {
        Fixture fixture = fixture();
        String json = "{\"items\":[{\"productId\":" + productId.replace("ID", fixture.product().getId().toString())
            + ",\"quantity\":" + quantity + "}]}";

        var response = customerOrder(fixture.userId(), json);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(mapper.readTree(response.getBody()).at("/meta/result").asText()).isEqualTo("FAIL");
        assertUnchanged(fixture);
    }

    static Stream<Arguments> invalidOrderInputs() {
        return Stream.of(
            Arguments.of("ID", "1.9"),
            Arguments.of("ID", "2147483648.0"),
            Arguments.of("ID", "null"),
            Arguments.of("ID.9", "1"),
            Arguments.of("9223372036854775808.0", "1")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {".0", "e0"})
    void acceptsExactIntegerValuesInDecimalAndExponentNotation(String suffix) throws Exception {
        Fixture fixture = fixture();
        String brandId = fixture.product().getBrandId() + suffix;
        MvcResult creation = admin(HttpMethod.POST, "/api-admin/v1/products",
            "{\"brandId\":" + brandId + ",\"name\":\"New\",\"price\":100" + suffix + "}");
        assertThat(creation.getResponse().getStatus()).isEqualTo(201);
        long productId = mapper.readTree(creation.getResponse().getContentAsString()).at("/data/id").asLong();
        assertThat(admin(HttpMethod.PUT, "/api-admin/v1/products/" + productId,
            "{\"name\":\"Changed\",\"price\":200" + suffix + "}").getResponse().getStatus()).isEqualTo(200);
        assertThat(admin(HttpMethod.PUT, "/api-admin/v1/products/" + productId + "/stock",
            "{\"quantity\":5" + suffix + "}").getResponse().getStatus()).isEqualTo(200);

        var response = customerOrder(fixture.userId(),
            "{\"items\":[{\"productId\":" + productId + suffix + ",\"quantity\":2" + suffix + "}]}");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        long orderId = mapper.readTree(response.getBody()).at("/data/id").asLong();
        Order saved = orders.findById(orderId).orElseThrow();
        assertThat(saved.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(productId);
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getUnitPrice()).isEqualTo(200L);
        });
        assertThat(saved.getTotalAmount()).isEqualTo(400L);
        assertThat(products.findById(productId).orElseThrow().getStock().amount()).isEqualTo(5L);
        assertThat(points.findByUserId(fixture.userId()).orElseThrow().getBalance().amount()).isEqualTo(1_000L);
    }

    private Fixture fixture() {
        User user = users.save(User.create());
        Brand brand = brands.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "Air", 100L);
        product.changeStockTo(5L);
        product = products.save(product);
        points.save(Point.create(user.getId(), new PointBalance(1_000L)));
        Order order = orders.save(Order.create(user.getId(), List.of(
            OrderItem.create(product.getId(), product.getName(), product.getPrice(), 1)
        )));
        return new Fixture(user.getId(), product, order);
    }

    private void assertUnchanged(Fixture fixture) {
        assertThat(products.findAll()).singleElement().satisfies(product -> {
            assertThat(product.getId()).isEqualTo(fixture.product().getId());
            assertThat(product.getName()).isEqualTo("Air");
            assertThat(product.getPrice()).isEqualTo(100L);
            assertThat(product.getStock().amount()).isEqualTo(5L);
        });
        assertThat(orders.findAll()).singleElement().satisfies(order -> {
            assertThat(order.getId()).isEqualTo(fixture.order().getId());
            assertThat(order.getStatus()).isEqualTo(fixture.order().getStatus());
            assertThat(order.getTotalAmount()).isEqualTo(100L);
            assertThat(order.getItems()).containsExactlyElementsOf(fixture.order().getItems());
            assertThat(order.getPaymentAmount()).isNull();
            assertThat(order.getPaymentResult()).isNull();
        });
        assertThat(points.findByUserId(fixture.userId()).orElseThrow().getBalance().amount()).isEqualTo(1_000L);
    }

    private MvcResult admin(HttpMethod method, String path, String json) throws Exception {
        return mvc.perform(request(method, path).with(user("admin").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private org.springframework.http.ResponseEntity<String> customerOrder(Long userId, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", userId.toString());
        return rest.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private record Fixture(Long userId, Product product, Order order) {}
}
