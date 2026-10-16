package com.loopers.config.experiment;

import com.loopers.config.redis.RedisConfig;
import com.loopers.product.application.ProductUseCase;
import com.loopers.product.interfaces.ProductReadFacade;
import com.loopers.support.cache.CacheKeys;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"catalog.cache.enabled=true"})
@ActiveProfiles({"test", "experiment"})
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class CacheHitRevisionHttpTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379);
    static { REDIS.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("datasource.redis.master.host", REDIS::getHost);
        r.add("datasource.redis.master.port", REDIS::getFirstMappedPort);
        r.add("datasource.redis.replicas[0].host", REDIS::getHost);
        r.add("datasource.redis.replicas[0].port", REDIS::getFirstMappedPort);
    }
    @Autowired com.loopers.support.cache.CacheVersions versions;
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired ProductReadFacade facade;
    @MockitoSpyBean ProductUseCase products;
    @MockitoSpyBean org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis;

    /** 준비: 쓰기 없는 목록을 캐시한다. 실행: facade hit과 HTTP hit. 확인: 상품 원본 조회 없이 revision SQL 2회로 같은 결과를 주며 인증은 유지한다. */
    @Test
    @DisplayName("상품 캐시 hit은 DB revision만 확인하고 HTTP 인증은 유지한다")
    void hitChecksRevisionsAndKeepsAuthentication() throws Exception {
        var fixture = new CommerceFixture(em, manager);
        try {
            var user = fixture.user();
            fixture.product(fixture.brand("cache hit"), "same response", 100, 1);
            String miss = mvc.perform(get("/api/v1/products").header("X-USER-ID", user.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            clearInvocations(products, jdbc);
            var trace = new ExperimentTrace();
            ExperimentTrace.begin(trace);
            try {
                assertThat(facade.page(null, null, "latest", 0, 20).data().content()).hasSize(1);
                assertThat(trace.queries).hasSize(2);
                assertThat(trace.queries).allSatisfy(query -> assertThat(query.sql()).contains("read_cache_revision"));
                assertThat(trace.timings()).containsKey("jdbc.acquire");
            } finally { ExperimentTrace.clear(); }
            String hit = mvc.perform(get("/api/v1/products").header("X-USER-ID", user.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(hit).isEqualTo(miss);
            verifyNoInteractions(products);
            mvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/products").header("X-USER-ID", "999999999")).andExpect(status().isUnauthorized());
            String key = versions.key(CacheKeys.products("latest", null, 0, 20), CacheKeys.CATALOG_GROUP);
            assertThat(key).startsWith("v4:").contains(":rev=");
            assertThat(redis.getExpire(CacheKeys.PRODUCTS + "::" + key)).isBetween(269L, 330L);
        } finally { fixture.truncateRemainingTables(); }
    }
}
