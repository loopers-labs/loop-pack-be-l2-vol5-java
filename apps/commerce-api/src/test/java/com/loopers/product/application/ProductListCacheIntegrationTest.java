package com.loopers.product.application;

import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.like.application.port.in.LikeCommandUseCase;
import com.loopers.order.application.OrderQueryService;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.application.port.in.OrderInfo;
import com.loopers.order.domain.OrderLines;
import com.loopers.payment.application.PaymentQueryService;
import com.loopers.payment.application.port.in.PaymentReconcileUseCase;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.product.application.port.in.ProductCommandUseCase;
import com.loopers.product.application.port.in.ProductInfo;
import com.loopers.product.domain.ProductSort;
import com.loopers.support.persistence.SqlRecorder;
import com.loopers.support.pg.LocalTestPgServer;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.tuple;

/**
 * W4 · 목록 캐시의 정상 경로: 실제 MySQL·Redis에서 miss → hit, 그리고 목록을 바꾸는 쓰기 뒤의 순차 조회.
 * 쓰기는 관리자·고객 API가 부르는 유스케이스를 그대로 호출한다 (캐시용 우회 서비스를 만들지 않는다).
 */
// W5: PG fixture 주소(또는 발송 mock) 때문에 이 클래스만 쓰는 컨텍스트가 생긴다. 다른 클래스가 재사용하지 않으므로 끝나면 닫아
// DB 커넥션을 돌려준다 — 캐시된 컨텍스트마다 Hikari 풀을 잡아 로컬 MySQL max_connections(151)를 넘기지 않게.
@DirtiesContext
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.loopers.support.persistence.SqlRecorder")
class ProductListCacheIntegrationTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);
    /** W5: 확정 주문은 PG 결제로 만든다 (로컬 테스트 PG fixture → 성공 → reconcile 반영). */
    private static final LocalTestPgServer PG = LocalTestPgServer.start();

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("pg.local.base-url", PG::baseUrl);
    }

    @AfterAll
    static void stopPg() {
        PG.stop();
    }

    @Autowired
    private ProductListQueryService productListQueryService;

    @Autowired
    private ProductQueryService productQueryService;

    @Autowired
    private BrandCommandUseCase brandCommandUseCase;

    @Autowired
    private ProductCommandUseCase productCommandUseCase;

    @Autowired
    private LikeCommandUseCase likeCommandUseCase;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private PaymentRequestUseCase paymentRequestUseCase;

    @Autowired
    private PaymentReconcileUseCase paymentReconcileUseCase;

    @Autowired
    private PaymentQueryService paymentQueryService;

    @Autowired
    private OrderCommandUseCase orderCommandUseCase;

    @Autowired
    private OrderQueryService orderQueryService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Long brand(String name) {
        return brandCommandUseCase.create(name, null).id();
    }

    private Long product(Long brandId, String name, long price) {
        return productCommandUseCase.create(brandId, name, price, 10).id();
    }

    private Page<ProductInfo> list(Long brandId, ProductSort sort) {
        return productListQueryService.getProducts(brandId, sort, FIRST_PAGE);
    }

    /** W5 확정: 결제 요청(TX A) → PG 거래 PENDING → fixture에서 SUCCESS → reconcile로 CONFIRMED. 포인트는 쓰지 않는다. */
    private void confirmByPg(Long userId, Long orderId) {
        paymentRequestUseCase.request(new PaymentRequestCommand(userId, orderId, "attempt-" + orderId, "SAMSUNG", "1234-5678-9814-1451"));
        await().atMost(Duration.ofSeconds(5))
            .until(() -> paymentQueryService.getMyPayment(userId, orderId).paymentStatus() == PaymentStatus.PENDING);
        PG.complete(paymentQueryService.getMyPayment(userId, orderId).transactionKey(), "SUCCESS", null);
        paymentReconcileUseCase.reconcile(userId, orderId);
    }

    private static List<Long> idsOf(Page<ProductInfo> page) {
        return page.getContent().stream().map(ProductInfo::id).toList();
    }

    @DisplayName("같은 조건의 두 번째 조회는 캐시에서 읽는다 — DB만 읽은 결과와 같고, 목록·좋아요 집계·건수 SQL을 보내지 않는다.")
    @Test
    void hitReturnsSameResultWithoutListQueries() {
        // arrange
        Long brandId = brand("브랜드");
        Long cheap = product(brandId, "싼", 1_000);
        Long expensive = product(brandId, "비싼", 2_000);
        likeCommandUseCase.like(1L, expensive);
        Page<ProductInfo> dbOnly = productQueryService.getProducts(brandId, ProductSort.LIKES_DESC, FIRST_PAGE);

        // act
        Page<ProductInfo> miss = list(brandId, ProductSort.LIKES_DESC);
        SqlRecorder.clear();
        Page<ProductInfo> hit = list(brandId, ProductSort.LIKES_DESC);

        // assert
        assertThat(miss.getContent()).isEqualTo(dbOnly.getContent());
        assertThat(hit.getContent()).isEqualTo(dbOnly.getContent());
        assertThat(hit.getTotalElements()).isEqualTo(dbOnly.getTotalElements());
        assertThat(idsOf(hit)).containsExactly(expensive, cheap);
        // hit에 남는 SQL은 비노출 확인용 PK 조회 하나뿐이다 — 목록·좋아요 조인·집계·건수·브랜드 조회는 나가지 않는다
        assertThat(SqlRecorder.statements()).singleElement().satisfies(sql -> assertThat(sql)
            .startsWith("select")
            .contains("from products")
            .contains(".id in (")
            .doesNotContain("likes")
            .doesNotContain("count(")
            .doesNotContain("brands"));
    }

    @DisplayName("앞 3페이지(0~2)만 캐시한다 — 뒤 페이지는 hit율이 낮아 매번 원본에서 읽고, 캐시 키도 늘리지 않는다.")
    @Test
    void onlyFrontPagesAreCached() {
        // arrange
        Long brandId = brand("브랜드");
        product(brandId, "A", 1_000);
        PageRequest lastCachedPage = PageRequest.of(2, 20);
        PageRequest firstUncachedPage = PageRequest.of(3, 20);
        productListQueryService.getProducts(brandId, ProductSort.LATEST, lastCachedPage);
        productListQueryService.getProducts(brandId, ProductSort.LATEST, firstUncachedPage);

        // act
        SqlRecorder.clear();
        productListQueryService.getProducts(brandId, ProductSort.LATEST, lastCachedPage);
        List<String> cachedPageSql = List.copyOf(SqlRecorder.statements());
        SqlRecorder.clear();
        productListQueryService.getProducts(brandId, ProductSort.LATEST, firstUncachedPage);
        List<String> uncachedPageSql = List.copyOf(SqlRecorder.statements());

        // assert — 건수 SQL이 나가는지로 원본 조회 여부를 본다
        assertThat(cachedPageSql).noneMatch(sql -> sql.contains("count("));
        assertThat(uncachedPageSql).anyMatch(sql -> sql.contains("count("));
    }

    @DisplayName("관리자가 가격을 바꾸고 commit한 뒤 조회하면 새 가격과 바뀐 가격순을 돌려준다.")
    @Test
    void priceChangeIsVisibleAfterCommit() {
        // arrange
        Long brandId = brand("브랜드");
        Long first = product(brandId, "A", 1_000);
        Long second = product(brandId, "B", 2_000);
        list(brandId, ProductSort.PRICE_ASC);

        // act
        productCommandUseCase.update(first, "A", 3_000);
        Page<ProductInfo> after = list(brandId, ProductSort.PRICE_ASC);

        // assert
        assertThat(after.getContent()).extracting(ProductInfo::id, ProductInfo::price)
            .containsExactly(tuple(second, 2_000L), tuple(first, 3_000L));
    }

    @DisplayName("가격을 바꿔도 이미 확정한 주문의 단가·합계·결제액은 그대로다 — 목록 캐시와 주문 금액은 다른 책임이다.")
    @Test
    void pastOrderAmountIsKeptAfterPriceChange() {
        // arrange
        Long brandId = brand("브랜드");
        Long productId = product(brandId, "A", 1_000);
        Long userId = userJpaRepository.save(new UserModel("고객")).getId();
        Long orderId = orderCommandUseCase.create(userId, List.of(new OrderLines.Line(productId, 2))).id();
        confirmByPg(userId, orderId);
        list(brandId, ProductSort.PRICE_ASC);

        // act
        productCommandUseCase.update(productId, "A", 9_000);

        // assert
        assertThat(list(brandId, ProductSort.PRICE_ASC).getContent()).extracting(ProductInfo::price).containsExactly(9_000L);
        OrderInfo order = orderQueryService.getMyOrder(userId, orderId);
        assertThat(order.items()).extracting(OrderInfo.Item::unitPrice, OrderInfo.Item::amount).containsExactly(tuple(1_000L, 2_000L));
        assertThat(order.totalAmount()).isEqualTo(2_000L);
        assertThat(order.paidAmount()).isEqualTo(2_000L);
    }

    @DisplayName("상품 생성·삭제 뒤 조회하면 목록 구성과 전체 건수가 바뀐다.")
    @Test
    void createAndDeleteChangeMembership() {
        // arrange
        Long brandId = brand("브랜드");
        Long kept = product(brandId, "A", 1_000);
        Long removed = product(brandId, "B", 2_000);
        list(brandId, ProductSort.LATEST);

        // act
        Long added = product(brandId, "C", 3_000);
        productCommandUseCase.delete(removed);
        Page<ProductInfo> after = list(brandId, ProductSort.LATEST);

        // assert
        assertThat(idsOf(after)).containsExactly(added, kept);
        assertThat(after.getTotalElements()).isEqualTo(2);
    }

    @DisplayName("캐시에 담긴 브랜드를 일괄 삭제하면 그 상품이 목록에서 빠지고, 다른 브랜드 상품과 그 상품의 과거 주문은 남는다.")
    @Test
    void brandDeleteRemovesItsProducts() {
        // arrange
        Long deletedBrand = brand("삭제될");
        Long otherBrand = brand("남을");
        Long deletedProduct = product(deletedBrand, "삭제될 상품", 1_000);
        Long otherProduct = product(otherBrand, "남을 상품", 2_000);
        Long userId = userJpaRepository.save(new UserModel("고객")).getId();
        Long orderId = orderCommandUseCase.create(userId, List.of(new OrderLines.Line(deletedProduct, 3))).id();
        confirmByPg(userId, orderId);
        list(null, ProductSort.LATEST);
        list(deletedBrand, ProductSort.LATEST);

        // act
        brandCommandUseCase.delete(deletedBrand);

        // assert
        Page<ProductInfo> all = list(null, ProductSort.LATEST);
        assertThat(idsOf(all)).containsExactly(otherProduct);
        assertThat(all.getTotalElements()).isEqualTo(1);
        assertThat(list(deletedBrand, ProductSort.LATEST).getContent()).isEmpty();
        // 삭제는 고객 목록·새 주문에서 빼는 것이지, 이미 확정한 주문을 지우거나 바꾸는 것이 아니다
        OrderInfo order = orderQueryService.getMyOrder(userId, orderId);
        assertThat(order.items()).extracting(OrderInfo.Item::productId, OrderInfo.Item::unitPrice, OrderInfo.Item::amount)
            .containsExactly(tuple(deletedProduct, 1_000L, 3_000L));
        assertThat(order.paidAmount()).isEqualTo(3_000L);
    }

    @DisplayName("브랜드 이름을 바꾸면 목록의 브랜드 이름도 바뀐다.")
    @Test
    void brandRenameIsVisible() {
        // arrange
        Long brandId = brand("옛 이름");
        product(brandId, "A", 1_000);
        list(null, ProductSort.LATEST);

        // act
        brandCommandUseCase.update(brandId, "새 이름", null);

        // assert
        assertThat(list(null, ProductSort.LATEST).getContent()).extracting(ProductInfo::brandName).containsExactly("새 이름");
    }

    @DisplayName("좋아요 등록·취소 뒤 조회하면 좋아요 수와 좋아요순이 바뀐다.")
    @Test
    void likesChangeCountAndOrder() {
        // arrange
        Long brandId = brand("브랜드");
        Long older = product(brandId, "먼저", 1_000);
        Long newer = product(brandId, "나중", 1_000);
        assertThat(idsOf(list(null, ProductSort.LIKES_DESC))).containsExactly(newer, older);

        // act & assert — 등록
        likeCommandUseCase.like(1L, older);
        likeCommandUseCase.like(2L, older);
        assertThat(list(null, ProductSort.LIKES_DESC).getContent()).extracting(ProductInfo::id, ProductInfo::likeCount)
            .containsExactly(tuple(older, 2L), tuple(newer, 0L));

        // act & assert — 취소
        likeCommandUseCase.unlike(1L, older);
        likeCommandUseCase.unlike(2L, older);
        assertThat(list(null, ProductSort.LIKES_DESC).getContent()).extracting(ProductInfo::id, ProductInfo::likeCount)
            .containsExactly(tuple(newer, 0L), tuple(older, 0L));
    }
}
