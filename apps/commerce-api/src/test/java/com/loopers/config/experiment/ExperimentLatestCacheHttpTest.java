package com.loopers.config.experiment;

import com.loopers.config.redis.RedisConfig;
import com.loopers.product.application.ProductUseCase;
import com.loopers.product.domain.ProductSort;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "experiment.latest-cache.enabled=true")
@ActiveProfiles({"test", "experiment"})
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class ExperimentLatestCacheHttpTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
        .withExposedPorts(6379);
    static { REDIS.start(); }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", REDIS::getFirstMappedPort);
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", REDIS::getFirstMappedPort);
    }

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager tm;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis;
    @MockitoSpyBean ProductUseCase products;
    private CommerceFixture fixture;
    private long userId;
    private long otherUserId;
    private long brandId;

    @BeforeEach void prepare() {
        redis.delete(ExperimentLatestCache.KEY);
        fixture = new CommerceFixture(em, tm);
        userId = fixture.user().getId();
        otherUserId = fixture.user().getId();
        var brand = fixture.brand("Cache brand");
        brandId = brand.getId();
        var product = fixture.product(brand, "Cache product", 1000, 3);
        fixture.like(fixture.user(), product);
        fixture.deletedProduct(brand, "Deleted", 2000);
        clearInvocations(products);
    }

    @AfterEach void cleanup() {
        redis.delete(ExperimentLatestCache.KEY);
        fixture.truncateRemainingTables();
    }

    @Test void missThenHitPreservesResponseAndSkipsOriginalQueryAcrossUsers() throws Exception {
        String miss = request(userId, "latest", 0, 20, null);
        assertThat(redis.getExpire(ExperimentLatestCache.KEY)).isBetween(1L, 600L);
        assertThat(request(otherUserId, "latest", 0, 20, null)).isEqualTo(miss);
        assertThat(request(userId, null, 0, 20, null)).isEqualTo(miss);
        assertThat(miss).contains("Cache product").doesNotContain("Deleted");
        verify(products, times(1)).findBasicPage(null, ProductSort.LATEST, 0, 20);
    }

    @Test void differentFiltersPagesSizesAndSortsBypassTrialKey() throws Exception {
        request(userId, "latest", 0, 20, null);
        request(userId, "latest", 0, 20, brandId);
        request(userId, "latest", 1, 20, null);
        request(userId, "latest", 0, 1, null);
        request(userId, "price_asc", 0, 20, null);
        verify(products).findBasicPage(brandId, ProductSort.LATEST, 0, 20);
        verify(products).findBasicPage(null, ProductSort.LATEST, 1, 20);
        verify(products).findBasicPage(null, ProductSort.LATEST, 0, 1);
        verify(products).findBasicPage(null, ProductSort.PRICE_ASC, 0, 20);
    }

    @Test void cachedResponseDoesNotBypassRequesterValidation() throws Exception {
        request(userId, "latest", 0, 20, null);
        mvc.perform(get("/api/v1/products")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/products").header("X-USER-ID", "99999999"))
            .andExpect(status().is4xxClientError());
        verify(products, times(1)).findBasicPage(null, ProductSort.LATEST, 0, 20);
    }

    @Test void malformedCacheFallsBackToOriginalQuery() throws Exception {
        redis.opsForValue().set(ExperimentLatestCache.KEY, "invalid-json");
        assertThat(request(userId, "latest", 0, 20, null)).contains("Cache product");
        verify(products).findBasicPage(null, ProductSort.LATEST, 0, 20);
    }

    private String request(long user, String sort, int page, int size, Long brand) throws Exception {
        var request = get("/api/v1/products").header("X-USER-ID", user)
            .param("page", String.valueOf(page)).param("size", String.valueOf(size));
        if (sort != null) request.param("sort", sort);
        if (brand != null) request.param("brandId", String.valueOf(brand));
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
