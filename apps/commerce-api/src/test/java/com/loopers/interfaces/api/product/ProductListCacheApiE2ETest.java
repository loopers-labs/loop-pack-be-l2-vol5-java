package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.application.order.OrderFacade.OrderRequestItem;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.admin.AdminMockMvcClient;
import com.loopers.interfaces.api.admin.product.ProductV1Dto;
import com.loopers.interfaces.api.order.OrderV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("관리자 API 변경 뒤 고객 상품 목록 캐시")
class ProductListCacheApiE2ETest {

    private static final String ADMIN_PRODUCTS = "/api-admin/v1/products";
    private static final String ADMIN_BRANDS = "/api-admin/v1/brands";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PointRepository pointRepository;

    private AdminMockMvcClient adminClient;
    private Long orderUserId;
    private Brand brand;
    private Product product;

    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
        adminClient = new AdminMockMvcClient(mockMvc, objectMapper);
        brand = brandRepository.save(Brand.create("Nike"));
        product = productRepository.save(Product.create(brand.getId(), "Air", 1_000L));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @Test
    @DisplayName("관리자 API로 가격을 수정하면 캐시된 가격순 목록에 새 가격과 순서가 반영되고, 수정 전에 확정한 주문 금액은 유지된다.")
    void reflectsNewPriceAndOrder_andKeepsPastOrderAmount_afterAdminPriceUpdate() throws Exception {
        Product other = productRepository.save(Product.create(brand.getId(), "Max", 2_000L));
        assertThat(customerList()).extracting(ProductCustomerV1Dto.ProductResponse::id)
            .containsExactly(product.getId(), other.getId());
        assertCustomerListCached();
        Long orderId = createAndConfirmOrder(product.getId(), 2);

        ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
            ADMIN_PRODUCTS + "/" + product.getId(),
            HttpMethod.PUT,
            new HttpEntity<>(new ProductV1Dto.UpdateRequest("Air", 3_000L)),
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(customerList())
            .extracting(ProductCustomerV1Dto.ProductResponse::id, ProductCustomerV1Dto.ProductResponse::price)
            .containsExactly(tuple(other.getId(), 2_000L), tuple(product.getId(), 3_000L));
        OrderV1Dto.OrderResponse order = getOrder(orderId);
        assertThat(order.totalAmount()).isEqualTo(2_000L);
        assertThat(order.paymentAmount()).isEqualTo(2_000L);
        assertThat(order.items()).extracting(OrderV1Dto.OrderItemResponse::unitPrice).containsExactly(1_000L);
    }

    @Test
    @DisplayName("관리자 API로 상품을 등록하면 캐시된 고객 목록에 새 상품을 반환한다.")
    void returnsRegisteredProduct_afterAdminRegister() throws Exception {
        warmUpCustomerList();

        ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response = adminClient.exchange(
            ADMIN_PRODUCTS,
            HttpMethod.POST,
            new HttpEntity<>(new ProductV1Dto.CreateRequest(brand.getId(), "Max", 2_000L)),
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(customerList()).extracting(ProductCustomerV1Dto.ProductResponse::id)
            .contains(response.getBody().data().id());
    }

    @Test
    @DisplayName("관리자 API로 브랜드를 삭제하면 캐시된 고객 목록에서 연결 상품을 반환하지 않는다.")
    void hidesBrandProducts_afterAdminBrandDelete() throws Exception {
        warmUpCustomerList();

        ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
            ADMIN_BRANDS + "/" + brand.getId(),
            HttpMethod.DELETE,
            null,
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(customerList()).isEmpty();
    }

    private void warmUpCustomerList() throws Exception {
        assertThat(customerList()).extracting(ProductCustomerV1Dto.ProductResponse::price).containsExactly(1_000L);
        assertCustomerListCached();
    }

    private void assertCustomerListCached() {
        assertThat(redisTemplate.keys("product:list:v1:*:brand=" + brand.getId() + ":*"))
            .as("관리자 변경 전에 고객 목록이 캐시에 저장돼 있다.")
            .isNotEmpty();
    }

    private Long createAndConfirmOrder(Long productId, int quantity) throws Exception {
        User user = userRepository.save(User.create());
        pointRepository.save(Point.create(user.getId(), new PointBalance(100_000L)));
        stockUp(productId);
        OrderV1Dto.CreateRequest request = new OrderV1Dto.CreateRequest(List.of(new OrderRequestItem(productId, quantity)));
        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                .header("X-USER-ID", user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(HttpStatus.CREATED.value());
        Long orderId = readData(created, new TypeReference<ApiResponse<OrderV1Dto.OrderResponse>>() {}).id();
        MvcResult confirmed = mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm").header("X-USER-ID", user.getId()))
            .andReturn();
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        orderUserId = user.getId();
        return orderId;
    }

    private void stockUp(Long productId) {
        ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
            ADMIN_PRODUCTS + "/" + productId + "/stock",
            HttpMethod.PUT,
            new HttpEntity<>(new ProductV1Dto.StockUpdateRequest(10L)),
            new ParameterizedTypeReference<>() {}
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private OrderV1Dto.OrderResponse getOrder(Long orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders/" + orderId).header("X-USER-ID", orderUserId)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        return readData(result, new TypeReference<ApiResponse<OrderV1Dto.OrderResponse>>() {});
    }

    private <T> T readData(MvcResult result, TypeReference<ApiResponse<T>> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type).data();
    }

    private List<ProductCustomerV1Dto.ProductResponse> customerList() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/products")
                .param("brandId", brand.getId().toString())
                .param("sort", "price_asc"))
            .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        ApiResponse<List<ProductCustomerV1Dto.ProductResponse>> body = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            new TypeReference<>() {}
        );
        return body.data();
    }
}
