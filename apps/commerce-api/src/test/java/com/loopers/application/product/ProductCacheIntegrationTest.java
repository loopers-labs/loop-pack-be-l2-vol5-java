package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSortType;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class ProductCacheIntegrationTest {

    @Autowired
    private ProductFacade productFacade;

    @MockitoSpyBean
    private ProductService productService;

    @MockitoSpyBean
    private ProductListCache productListCache;

    @MockitoSpyBean
    private ProductCache productCache;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Long nikeId;
    private Long adidasId;
    private Long slipperId;

    @BeforeEach
    void setUp() {
        nikeId = brandJpaRepository.save(new BrandModel("나이키")).getId();
        adidasId = brandJpaRepository.save(new BrandModel("아디다스")).getId();
        Long runner = productJpaRepository.save(new ProductModel(nikeId, "runner", 30_000L, 5)).getId();
        slipperId = productJpaRepository.save(new ProductModel(nikeId, "slipper", 10_000L, 3)).getId();
        productJpaRepository.save(new ProductModel(nikeId, "cap", 20_000L, 0));
        productJpaRepository.save(new ProductModel(adidasId, "jacket", 50_000L, 7));
        likeJpaRepository.save(new LikeModel(1L, runner));
        likeJpaRepository.save(new LikeModel(2L, runner));
        likeJpaRepository.save(new LikeModel(1L, slipperId));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("같은 조건이면 DB 직접 조회·miss·hit의 결과가 같고, hit에서는 목록·건수·상품·좋아요 조회를 하지 않는다.")
    @Test
    void returnsSameResultForDbOnlyMissAndHit_andSkipsDbQueriesOnHit() {
        // arrange — 두 캐시를 모두 거치지 않는 DB 직접 조회 결과
        doReturn(Optional.empty()).when(productListCache).find(any());
        doNothing().when(productListCache).save(any(), any());
        doReturn(Map.of()).when(productCache).findAll(any());
        doNothing().when(productCache).saveAll(any(), anyList());
        Page<ProductInfo> dbOnly = getNikePriceAsc();
        reset(productListCache, productCache);
        clearInvocations(productService);
        double listMissesBefore = cacheCount("product.list.cache", "miss");
        double listHitsBefore = cacheCount("product.list.cache", "hit");
        double productHitsBefore = cacheCount("product.cache", "hit");

        // act
        Page<ProductInfo> miss = getNikePriceAsc();
        Page<ProductInfo> hit = getNikePriceAsc();

        // assert
        assertAll(
            () -> assertThat(dbOnly.getContent()).extracting(ProductInfo::name).containsExactly("slipper", "cap", "runner"),
            () -> assertThat(dbOnly.getContent()).extracting(ProductInfo::likeCount).containsExactly(1L, 0L, 2L),
            () -> assertThat(miss.getContent()).isEqualTo(dbOnly.getContent()),
            () -> assertThat(hit.getContent()).isEqualTo(dbOnly.getContent()),
            () -> assertThat(hit.getTotalElements()).isEqualTo(dbOnly.getTotalElements()),
            () -> verify(productService, times(1)).getProducts(any(), any(), any()),
            () -> verify(productService, never()).getActiveProducts(anyList()),
            () -> assertThat(cacheCount("product.list.cache", "miss") - listMissesBefore).isEqualTo(1),
            () -> assertThat(cacheCount("product.list.cache", "hit") - listHitsBefore).isEqualTo(1),
            () -> assertThat(cacheCount("product.cache", "hit") - productHitsBefore).as("상품 3개 모두 상품 캐시 hit").isEqualTo(3)
        );
    }

    @DisplayName("재고가 바뀌면, 목록 순서는 그대로라 목록 캐시를 그대로 쓰고 바뀐 상품만 DB에서 다시 읽는다.")
    @Test
    void reusesListCacheAndReloadsOnlyChangedProduct_whenStockChanges() {
        // arrange
        getNikePriceAsc();
        clearInvocations(productService);

        // act
        productFacade.changeStock(slipperId, 9);
        Page<ProductInfo> after = getNikePriceAsc();

        // assert
        assertAll(
            () -> verify(productService, never()).getProducts(any(), any(), any()),
            () -> verify(productService, times(1)).getActiveProducts(List.of(slipperId)),
            () -> assertThat(after.getContent()).extracting(ProductInfo::name).containsExactly("slipper", "cap", "runner"),
            () -> assertThat(after.getContent().get(0).stock()).isEqualTo(9)
        );
    }

    @DisplayName("브랜드·정렬·페이지·크기가 하나라도 다르면, 다른 목록 캐시 항목을 쓴다.")
    @Test
    void usesSeparateEntries_whenAnyConditionDiffers() {
        // arrange
        Page<ProductInfo> firstPage = productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(0, 2));
        clearInvocations(productService);

        // act
        Page<ProductInfo> secondPage = productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(1, 2));
        Page<ProductInfo> otherSort = productFacade.getProducts(nikeId, ProductSortType.LIKES_DESC, PageRequest.of(0, 2));
        Page<ProductInfo> otherBrand = productFacade.getProducts(adidasId, ProductSortType.PRICE_ASC, PageRequest.of(0, 2));
        Page<ProductInfo> allBrands = productFacade.getProducts(null, ProductSortType.PRICE_ASC, PageRequest.of(0, 2));
        Page<ProductInfo> otherSize = productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(0, 3));

        // assert
        assertAll(
            () -> verify(productService, times(5)).getProducts(any(), any(), any()),
            () -> assertThat(names(firstPage)).containsExactly("slipper", "cap"),
            () -> assertThat(names(secondPage)).containsExactly("runner"),
            () -> assertThat(names(otherSort)).containsExactly("runner", "slipper"),
            () -> assertThat(names(otherBrand)).containsExactly("jacket"),
            () -> assertThat(names(allBrands)).containsExactly("slipper", "cap"),
            () -> assertThat(names(otherSize)).containsExactly("slipper", "cap", "runner")
        );
    }

    @DisplayName("목록 캐시는 앞 5페이지(0~4)까지만 쓰고, 그 뒤 페이지는 매번 DB에서 읽는다.")
    @Test
    void cachesOnlyFirstFivePages() {
        // arrange
        for (int i = 0; i < 4; i++) {
            productJpaRepository.save(new ProductModel(nikeId, "extra-" + i, 40_000L + i, 1));
        }
        double listGetsBefore = listCacheGets();

        // act
        Page<ProductInfo> lastCachedPage = productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(4, 1));
        productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(4, 1));
        clearInvocations(productService);
        Page<ProductInfo> firstUncachedPage = productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(5, 1));
        productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(5, 1));

        // assert
        assertAll(
            () -> assertThat(names(lastCachedPage)).containsExactly("extra-1"),
            () -> assertThat(names(firstUncachedPage)).containsExactly("extra-2"),
            () -> assertThat(listCacheGets() - listGetsBefore).as("4페이지 두 번만 목록 캐시를 본다").isEqualTo(2),
            () -> verify(productService, times(2)).getProducts(any(), any(), any())
        );
    }

    private Page<ProductInfo> getNikePriceAsc() {
        return productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(0, 20));
    }

    private List<String> names(Page<ProductInfo> page) {
        return page.getContent().stream().map(ProductInfo::name).toList();
    }

    private double cacheCount(String metric, String result) {
        return meterRegistry.counter(metric, "op", "get", "result", result).count();
    }

    private double listCacheGets() {
        return cacheCount("product.list.cache", "hit") + cacheCount("product.list.cache", "miss")
            + cacheCount("product.list.cache", "error");
    }
}
