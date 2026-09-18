package com.loopers.interfaces.api.admin;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.product.Price;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminListApiTest {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    private final MockMvc mvc;
    private final PointFacade pointFacade;
    private final OrderFacade orderFacade;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;

    @Autowired
    AdminListApiTest(
        MockMvc mvc,
        PointFacade pointFacade,
        OrderFacade orderFacade,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade
    ) {
        this.mvc = mvc;
        this.pointFacade = pointFacade;
        this.orderFacade = orderFacade;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.with(user("admin").roles("ADMIN"));
    }

    @Nested
    @DisplayName("브랜드 목록 — Q-1")
    class Brands {
        @DisplayName("기본은 삭제된 브랜드를 제외한다")
        @Test
        void excludesDeletedByDefault() throws Exception {
            brandFacade.register("무신사", "패션 플랫폼");
            Long removed = brandFacade.register("29CM", "셀렉트샵").getId();
            brandFacade.delete(removed);

            mvc.perform(asAdmin(get("/api-admin/v1/brands")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("무신사"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        }

        @DisplayName("삭제된 브랜드는 어떤 파라미터로도 다시 나오지 않는다")
        @Test
        void neverShowsDeleted() throws Exception {
            brandFacade.register("무신사", "패션 플랫폼");
            Long removed = brandFacade.register("29CM", "셀렉트샵").getId();
            brandFacade.delete(removed);

            mvc.perform(asAdmin(get("/api-admin/v1/brands?includeDeleted=true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("무신사"));
        }
    }

    @Nested
    @DisplayName("상품 목록")
    class Products {
        @DisplayName("관리자 목록에는 재고 수량이 있다 — 고객 목록과 타입이 다르다")
        @Test
        void hasQuantity() throws Exception {
            Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
            Long productId = productFacade.register(brandId, "코트", Price.of(129_000)).getId();
            productFacade.adjustStock(productId, Quantity.of(7));

            mvc.perform(asAdmin(get("/api-admin/v1/products")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].quantity").value(7))
                .andExpect(jsonPath("$.data.items[0].brandName").value("무신사"));
        }

        @DisplayName("brandId 로 거를 수 있고, 삭제된 상품은 기본 제외다")
        @Test
        void filtersByBrandAndExcludesDeleted() throws Exception {
            Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
            Long other = brandFacade.register("29CM", "셀렉트샵").getId();
            productFacade.register(brandId, "코트", Price.of(129_000));
            productFacade.register(other, "셔츠", Price.of(59_000));
            Long removed = productFacade.register(brandId, "니트", Price.of(89_000)).getId();
            productFacade.delete(removed);

            mvc.perform(asAdmin(get("/api-admin/v1/products?brandId=" + brandId)))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("코트"));

            mvc.perform(asAdmin(get("/api-admin/v1/products?brandId=" + brandId + "&includeDeleted=true")))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        }
    }

    @Nested
    @DisplayName("주문 목록 — ORDER-021")
    class Orders {
        private Long placeOrder(Long userId, Long productId) {
            pointFacade.charge(userId, ChargeAmount.of(100_000), NOW);
            return orderFacade.place(new OrderCreateCommand(userId,
                List.of(new OrderCreateCommand.Line(productId, OrderQuantity.of(1)))), NOW).getId();
        }

        private Long product() {
            Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
            Long productId = productFacade.register(brandId, "코트", Price.of(1_000)).getId();
            productFacade.adjustStock(productId, Quantity.of(100));
            return productId;
        }

        @DisplayName("ORDER-021 · 관리자 응답에는 구매자가 있다. 고객 응답에는 없다")
        @Test
        void showsBuyer() throws Exception {
            Long productId = product();
            placeOrder(7L, productId);

            mvc.perform(asAdmin(get("/api-admin/v1/orders")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].userId").value(7))
                .andExpect(jsonPath("$.data.items[0].items.length()").value(1));
        }

        @DisplayName("구매자로 거를 수 있다")
        @Test
        void filtersByBuyer() throws Exception {
            Long productId = product();
            placeOrder(7L, productId);
            placeOrder(8L, productId);

            mvc.perform(asAdmin(get("/api-admin/v1/orders?userId=7")))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].userId").value(7));
        }

        @DisplayName("상태로 거를 수 있다")
        @Test
        void filtersByStatus() throws Exception {
            Long productId = product();
            Long confirmed = placeOrder(7L, productId);
            placeOrder(8L, productId);
            orderFacade.confirm(7L, confirmed, NOW);

            mvc.perform(asAdmin(get("/api-admin/v1/orders?status=CONFIRMED")))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(confirmed))
                .andExpect(jsonPath("$.data.items[0].paidAmount").value(1000));
        }

        @DisplayName("상세도 구매자와 결제 결과를 함께 돌려준다")
        @Test
        void showsDetail() throws Exception {
            Long productId = product();
            Long orderId = placeOrder(7L, productId);
            orderFacade.confirm(7L, orderId, NOW);

            mvc.perform(asAdmin(get("/api-admin/v1/orders/" + orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paidAmount").value(1000));
        }

        @DisplayName("관리자는 남의 주문도 본다 — 고객의 404 와 다른 지점이다")
        @Test
        void seesAnyOrder() throws Exception {
            Long productId = product();
            Long orderId = placeOrder(7L, productId);

            mvc.perform(asAdmin(get("/api-admin/v1/orders/" + orderId)))
                .andExpect(status().isOk());
        }
    }
}
