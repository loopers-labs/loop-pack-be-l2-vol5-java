package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.CustomerProductListReader;
import com.loopers.application.product.ProductListCache;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.interfaces.api.ApiResponse;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("DB까지 실패할 때 상품 목록 오류 계약")
class ProductListCacheDbFailureE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandRepository brandRepository;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @MockitoSpyBean
    private CustomerProductListReader customerProductListReader;

    @MockitoSpyBean
    private ProductListCache productListCache;

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Brand brand;

    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
        brand = brandRepository.save(Brand.create("Nike"));
        productRepository.save(Product.create(brand.getId(), "Air", 1_000L));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @Test
    @DisplayName("Redis와 DB가 모두 실패하면 빈 목록 대신 기존 오류 응답을 반환한다.")
    void returnsErrorContract_whenRedisAndDbFail() throws Exception {
        doThrow(new RedisConnectionFailureException("Redis 장애")).when(productListCache).currentVersion(any());
        failDbListQuery();

        assertErrorContract(requestList());
    }

    @Test
    @DisplayName("캐시 miss에서 DB가 실패하면 기존 오류 응답을 반환하고 아무것도 캐시하지 않는다.")
    void returnsErrorContractWithoutCaching_whenDbFailsOnMiss() throws Exception {
        failDbListQuery();

        assertErrorContract(requestList());
        assertThat(redisTemplate.keys("product:list:v1:*")).isEmpty();
    }

    @Test
    @DisplayName("캐시 hit에서 삭제 확인 DB 조회가 실패하면 확인하지 못한 캐시 결과를 반환하지 않고 기존 오류 응답을 반환한다.")
    void returnsErrorContract_whenDeletionCheckFailsOnHit() throws Exception {
        assertThat(requestList().getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(redisTemplate.keys("product:list:v1:*")).isNotEmpty();
        doThrow(new CannotGetJdbcConnectionException("DB 연결 실패")).when(productRepository).countActiveByIds(anyList());

        assertErrorContract(requestList());
    }

    private void failDbListQuery() {
        doThrow(new CannotGetJdbcConnectionException("DB 연결 실패"))
            .when(customerProductListReader).read(any(), any(), anyInt(), anyInt());
    }

    private MvcResult requestList() throws Exception {
        return mockMvc.perform(get("/api/v1/products")
                .param("brandId", brand.getId().toString())
                .param("sort", "price_asc"))
            .andReturn();
    }

    private void assertErrorContract(MvcResult result) throws Exception {
        ApiResponse<List<Object>> body = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            new TypeReference<>() {}
        );
        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL);
        assertThat(body.meta().message()).isEqualTo("일시적인 오류가 발생했습니다.");
        assertThat(body.data()).as("빈 목록이나 기본값으로 감추지 않는다.").isNull();
    }
}
