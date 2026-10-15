package com.loopers.application.product;

import com.loopers.application.like.LikeFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.cache.CacheRedisConfig;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 고객 상품 조회의 cache-aside 동작을 DB 호출 횟수(Service 스파이)와 Redis 상태로 확인한다 (docs/week4/design.md 6·8번 섹션).
// 응답 값이 캐시 hit/miss와 관계없이 같은지는 같은 조회의 두 결과를 비교해 확인한다.
@SpringBootTest
class ProductCacheReadTest {

    private static final Pageable FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private LikeFacade likeFacade;

    @MockitoSpyBean
    private ProductService productService;

    @MockitoSpyBean
    private BrandService brandService;

    @MockitoSpyBean
    private LikeService likeService;

    @MockitoSpyBean
    private ProductCache productCache;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    @Qualifier(CacheRedisConfig.CACHE_REDIS_TEMPLATE)
    private RedisTemplate<String, String> cacheRedisTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private BrandModel nike;
    private ProductModel airMax;
    private ProductModel jordan;
    private ProductModel ultraBoost;

    @BeforeEach
    void setUp() {
        nike = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
        BrandModel adidas = brandJpaRepository.save(new BrandModel("아디다스", "스포츠 브랜드", "신발/의류"));
        airMax = productJpaRepository.save(new ProductModel("에어맥스", 1_000L, nike.getId(), 10));
        jordan = productJpaRepository.save(new ProductModel("조던", 2_000L, nike.getId(), 10));
        ultraBoost = productJpaRepository.save(new ProductModel("울트라부스트", 3_000L, adidas.getId(), 10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private boolean cached(String key) {
        return Boolean.TRUE.equals(cacheRedisTemplate.hasKey(key));
    }

    private Page<ProductDetailInfo> listAll() {
        return productFacade.getProductList(null, ProductSort.PRICE_ASC, FIRST_PAGE);
    }

    @DisplayName("상품 상세를 조회할 때,")
    @Nested
    class Detail {
        @DisplayName("C1 — 같은 상세를 두 번 조회하면, 두 번째는 상품·좋아요 수·브랜드를 DB에서 읽지 않고 같은 응답을 준다.")
        @Test
        void secondRead_hitsCache() {
            // arrange
            ProductDetailInfo first = productFacade.getProductDetail(airMax.getId());
            clearInvocations(productService, brandService, likeService);

            // act
            ProductDetailInfo second = productFacade.getProductDetail(airMax.getId());

            // assert
            assertAll(
                () -> assertThat(second).isEqualTo(first),
                () -> assertThat(second.brand().name()).isEqualTo("나이키"),
                () -> verify(productService, never()).getProduct(anyLong()),
                () -> verify(likeService, never()).getLikeCount(anyLong()),
                () -> verify(brandService, never()).getBrand(anyLong())
            );
        }

        @DisplayName("삭제된 상품이면, 여전히 NOT_FOUND이고 캐시에 아무것도 남지 않는다.")
        @Test
        void deletedProduct_notFoundAndNotCached() {
            // arrange
            airMax.delete();
            productJpaRepository.save(airMax);

            // act
            CoreException result = assertThrows(CoreException.class, () -> productFacade.getProductDetail(airMax.getId()));

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                () -> assertThat(cached("product:" + airMax.getId())).isFalse(),
                () -> assertThat(cached("brand:" + nike.getId())).isFalse()
            );
        }

        @DisplayName("상품 키 조회에서 Redis가 실패하면, 그 요청의 남은 캐시 호출을 건너뛰고 DB 결과로 응답한다.")
        @Test
        void skipsRemainingCacheCalls_whenUnavailable() {
            // arrange
            doReturn(CacheResult.unavailable()).when(productCache).getProducts(any());

            // act
            ProductDetailInfo result = productFacade.getProductDetail(airMax.getId());

            // assert
            assertAll(
                () -> assertThat(result.name()).isEqualTo("에어맥스"),
                () -> assertThat(result.brand().name()).isEqualTo("나이키"),
                () -> verify(productCache, times(1)).getProducts(any()),
                () -> verify(productCache, never()).putProducts(any()),
                () -> verify(productCache, never()).getBrands(any()),
                () -> verify(productCache, never()).putBrands(any()),
                () -> assertThat(cached("product:" + airMax.getId())).isFalse()
            );
        }

        @DisplayName("캐시된 상태에서 좋아요를 누르면, 캐시가 만료될 때까지(=Redis 비우기) 이전 좋아요 수가 보이고 만료 뒤 새 값이 보인다.")
        @Test
        void like_reflectedAfterCacheExpires() {
            // arrange
            Long userId = userJpaRepository.save(new UserModel()).getId();
            assertThat(productFacade.getProductDetail(airMax.getId()).likeCount()).isZero();

            // act
            likeFacade.likeProduct(userId, airMax.getId());
            long beforeExpiry = productFacade.getProductDetail(airMax.getId()).likeCount();
            redisCleanUp.truncateAll(); // TTL 만료를 흉내 낸다 (설계 6번 섹션 "계약 변경 — 좋아요 반영 지연")
            long afterExpiry = productFacade.getProductDetail(airMax.getId()).likeCount();

            // assert
            assertAll(
                () -> assertThat(beforeExpiry).isZero(),
                () -> assertThat(afterExpiry).isEqualTo(1L)
            );
        }
    }

    @DisplayName("상품 목록을 조회할 때,")
    @Nested
    class ProductList {
        @DisplayName("C1 — 같은 목록을 두 번 조회하면, 두 번째는 DB를 읽지 않고 순서·건수까지 같은 응답을 준다.")
        @Test
        void secondRead_hitsCache() {
            // arrange
            Page<ProductDetailInfo> first = listAll();
            clearInvocations(productService, brandService, likeService);

            // act
            Page<ProductDetailInfo> second = listAll();

            // assert
            assertAll(
                () -> assertThat(second.getContent()).containsExactlyElementsOf(first.getContent()),
                () -> assertThat(second.getContent()).extracting(ProductDetailInfo::id)
                    .containsExactly(airMax.getId(), jordan.getId(), ultraBoost.getId()),
                () -> assertThat(second.getTotalElements()).isEqualTo(first.getTotalElements()).isEqualTo(3L),
                () -> verify(productService, never()).getProducts(any(), any(), any()),
                () -> verify(productService, never()).getProductsByIds(anyList()),
                () -> verify(likeService, never()).getLikeCounts(anyList()),
                () -> verify(brandService, never()).getBrandsByIds(anyList())
            );
        }

        @DisplayName("목록 키는 있고 상품 키 하나만 없으면, 페이지 쿼리 없이 그 상품만 DB에서 읽는다.")
        @Test
        void partialMiss_loadsOnlyMissingProduct() {
            // arrange
            Page<ProductDetailInfo> first = listAll();
            cacheRedisTemplate.delete("product:" + jordan.getId());
            clearInvocations(productService, brandService, likeService);

            // act
            Page<ProductDetailInfo> second = listAll();

            // assert
            assertAll(
                () -> assertThat(second.getContent()).containsExactlyElementsOf(first.getContent()),
                () -> verify(productService, never()).getProducts(any(), any(), any()),
                () -> verify(productService, times(1)).getProductsByIds(anyList()),
                () -> verify(productService).getProductsByIds(List.of(jordan.getId())),
                () -> verify(likeService).getLikeCounts(List.of(jordan.getId())),
                () -> verify(brandService, never()).getBrandsByIds(anyList()),
                () -> assertThat(cached("product:" + jordan.getId())).isTrue()
            );
        }

        @DisplayName("목록 조회가 상품 키를 채우므로, 그 페이지 상품의 상세는 DB를 읽지 않는다.")
        @Test
        void listFillsDetailCache() {
            // arrange
            listAll();
            clearInvocations(productService, brandService, likeService);

            // act
            ProductDetailInfo detail = productFacade.getProductDetail(jordan.getId());

            // assert
            assertAll(
                () -> assertThat(detail.name()).isEqualTo("조던"),
                () -> assertThat(detail.brand().name()).isEqualTo("나이키"),
                () -> verify(productService, never()).getProduct(anyLong()),
                () -> verify(likeService, never()).getLikeCount(anyLong()),
                () -> verify(brandService, never()).getBrand(anyLong())
            );
        }

        @DisplayName("목록 키 조회에서 Redis가 실패하면, 그 요청의 남은 캐시 호출을 건너뛰고 DB 결과로 응답한다.")
        @Test
        void skipsRemainingCacheCalls_whenUnavailable() {
            // arrange
            doReturn(new ProductListLookup(null, CacheResult.unavailable()))
                .when(productCache).lookupProductIds(any(), any(), anyInt(), anyInt());

            // act
            Page<ProductDetailInfo> result = listAll();

            // assert
            assertAll(
                () -> assertThat(result.getContent()).extracting(ProductDetailInfo::id)
                    .containsExactly(airMax.getId(), jordan.getId(), ultraBoost.getId()),
                () -> assertThat(result.getContent()).extracting(info -> info.brand().name())
                    .containsExactly("나이키", "나이키", "아디다스"),
                () -> assertThat(result.getTotalElements()).isEqualTo(3L),
                () -> verify(productCache, never()).getProducts(any()),
                () -> verify(productCache, never()).getBrands(any()),
                () -> verify(productCache, never()).putProductIds(any(), any()),
                () -> verify(productCache, never()).putProducts(any()),
                () -> verify(productCache, never()).putBrands(any()),
                () -> assertThat(cached("product:" + airMax.getId())).isFalse()
            );
        }
    }
}
