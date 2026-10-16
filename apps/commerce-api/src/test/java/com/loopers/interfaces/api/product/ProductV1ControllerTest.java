package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.CustomerProductInfo;
import com.loopers.application.product.ProductPage;
import com.loopers.application.product.ProductQueryService;
import com.loopers.application.product.ProductSort;
import com.loopers.application.product.ProductListItemInfo;
import com.loopers.application.product.ProductListQueryService;
import com.loopers.application.brand.BrandInfo;
import com.loopers.config.AdminRequestInterceptor;
import com.loopers.config.WebMvcConfig;
import com.loopers.interfaces.api.admin.product.AdminProductV1Controller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ProductV1Controller.class, AdminProductV1Controller.class})
@Import({WebMvcConfig.class, AdminRequestInterceptor.class})
class ProductV1ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductFacade productFacade;

    @MockitoBean
    private ProductQueryService productQueryService;

    @MockitoBean
    private ProductListQueryService productListQueryService;

    @Test
    void returnsProductDetail() throws Exception {
        when(productQueryService.get(1L)).thenReturn(
            new CustomerProductInfo(1L, new BrandInfo(2L, "나이키", null), "운동화", 100_000L, 3L, null)
        );

        mockMvc.perform(get("/api/v1/products/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.brand.id").value(2))
            .andExpect(jsonPath("$.data.brand.name").value("나이키"))
            .andExpect(jsonPath("$.data.likeCount").value(3))
            .andExpect(jsonPath("$.data.stockQuantity").doesNotExist())
            .andExpect(jsonPath("$.data.isLiked").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(ProductSort.class)
    void returnsProductsWithPageAndSortWithoutTotalCount(ProductSort sort) throws Exception {
        Long brandId = sort == ProductSort.POPULAR ? 2L : null;
        when(productListQueryService.getList(null, brandId, 0, 20, sort)).thenReturn(
            new ProductPage<>(
                java.util.List.of(new ProductListItemInfo(new CustomerProductInfo(
                    1L, new BrandInfo(2L, "나이키", null), "운동화", 100_000L, 3L, null
                ), false)),
                0,
                20
            )
        );

        var request = get("/api/v1/products").param("sort", sort.name().toLowerCase(java.util.Locale.ROOT));
        if (brandId != null) { request.param("brandId", brandId.toString()); }
        mockMvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].likeCount").value(3))
            .andExpect(jsonPath("$.data.items[0].isLiked").value(false))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalCount").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(ProductSort.class)
    void returnsPersonalLikeStateForTheRequester(ProductSort sort) throws Exception {
        when(productListQueryService.getList(7L, 2L, 0, 20, sort)).thenReturn(new ProductPage<>(
            java.util.List.of(new ProductListItemInfo(
                new CustomerProductInfo(1L, new BrandInfo(2L, "brand", null), "product", 1000, 3, null), true)),
            0, 20));
        mockMvc.perform(get("/api/v1/products").header("X-USER-ID", "7").param("brandId", "2")
                .param("sort", sort.name().toLowerCase(java.util.Locale.ROOT)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].isLiked").value(true))
            .andExpect(jsonPath("$.data.items[0].likeCount").value(3))
            .andExpect(jsonPath("$.data.totalCount").doesNotExist());
    }

    @Test
    void rejectsMalformedRequesterHeader() throws Exception {
        mockMvc.perform(get("/api/v1/products").header("X-USER-ID", "not-a-number"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createsProductWithCreatedStatus() throws Exception {
        when(productFacade.create(2L, "운동화", 100_000L, 10))
            .thenReturn(new ProductInfo(1L, 2L, "운동화", 100_000L, 10));

        mockMvc.perform(post("/api-admin/v1/products")
                .header("X-USER-ROLE", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"brandId":2,"name":"운동화","price":100000,"initialStockQuantity":10}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.id").value(1))
            .andExpect(jsonPath("$.data.stockQuantity").value(10));
    }

    @Test
    void returnsAdminProductListWithStock() throws Exception {
        when(productFacade.getAll()).thenReturn(java.util.List.of(
            new ProductInfo(1L, 2L, "운동화", 100_000L, 10)
        ));

        mockMvc.perform(get("/api-admin/v1/products").header("X-USER-ROLE", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].stockQuantity").value(10));
    }

    @Test
    void returnsAdminProductDetailWithStock() throws Exception {
        when(productFacade.get(1L)).thenReturn(new ProductInfo(1L, 2L, "운동화", 100_000L, 10));

        mockMvc.perform(get("/api-admin/v1/products/1").header("X-USER-ROLE", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stockQuantity").value(10));
    }

    @Test
    void changesStockWithOkStatus() throws Exception {
        when(productFacade.changeStock(1L, 3))
            .thenReturn(new ProductInfo(1L, 2L, "운동화", 100_000L, 3));

        mockMvc.perform(put("/api-admin/v1/products/1/stock")
                .header("X-USER-ROLE", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                {"quantity":3}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stockQuantity").value(3));
    }

    @Test
    void updatesProductWithOkStatus() throws Exception {
        when(productFacade.update(1L, "러닝화", 120_000L))
            .thenReturn(new ProductInfo(1L, 2L, "러닝화", 120_000L, 10));

        mockMvc.perform(put("/api-admin/v1/products/1")
                .header("X-USER-ROLE", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"러닝화","price":120000}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("러닝화"))
            .andExpect(jsonPath("$.data.price").value(120_000));
    }

    @Test
    void deletesProductWithNoContentStatus() throws Exception {
        mockMvc.perform(delete("/api-admin/v1/products/1").header("X-USER-ROLE", "ADMIN"))
            .andExpect(status().isNoContent())
            .andExpect(jsonPath("$").doesNotExist());
    }
}
