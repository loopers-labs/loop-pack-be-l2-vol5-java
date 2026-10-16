package com.loopers.product.interfaces;

import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import static com.loopers.support.http.ApiHttp.customer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class ProductCombinedHttpTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    CommerceFixture fixture;
    @BeforeEach void setup() { fixture = new CommerceFixture(em, transactions); }
    @AfterEach void cleanup() { fixture.truncateRemainingTables(); }

    /** 준비: 좋아요 1개·재고 0 상품. 실행: 목록·상세·내 좋아요 조회. 확인: 한 응답에 표시 값 포함. */
    @Test void returnsCombinedProductOnEveryCustomerRoute() throws Exception {
        var user = fixture.user();
        var product = fixture.product(fixture.brand("combined"), "product", 100L, 0);
        fixture.like(user, product);
        for (String url : java.util.List.of("/api/v1/products", "/api/v1/users/" + user.getId() + "/likes", "/api/v1/products/" + product.getId())) {
            String item = url.endsWith("/" + product.getId()) ? "$.data" : "$.data.content[0]";
            mvc.perform(get(url).with(customer(user))).andExpect(status().isOk())
                .andExpect(jsonPath(item + ".id").value(product.getId()))
                .andExpect(jsonPath(item + ".likeCount").value(1))
                .andExpect(jsonPath(item + ".soldOut").value(true))
                .andExpect(jsonPath(item + ".stock").doesNotExist());
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
    }

    /** 폐지한 batch URL이 외부 API로 남지 않는지 확인한다. */
    @ParameterizedTest
    @ValueSource(strings = {"like-counts", "stocks"})
    void retiredBatchRoutesAreNotExposed(String path) throws Exception {
        mvc.perform(get("/api/v1/products/" + path).param("ids", "1").with(customer(fixture.user())))
            .andExpect(status().isBadRequest()); // productId 숫자 검증만 적용되고 batch handler는 없다.
    }
}
