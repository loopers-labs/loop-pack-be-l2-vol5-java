package com.loopers.application.brand;

import com.loopers.application.like.LikeApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.OrderResult;
import com.loopers.application.point.PointApplicationService;
import com.loopers.application.product.CustomerProductResult;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.application.product.ProductNotFoundException;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.brand.BrandId;
import com.loopers.domain.common.RuleViolationException;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import com.loopers.infrastructure.product.ProductPersistenceAdapter;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(BrandRemovalTransactionTest.FailureInjectionConfig.class)
class BrandRemovalTransactionTest {
    @Autowired
    private BrandApplicationService brandApplicationService;

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private PointApplicationService pointApplicationService;

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private LikeApplicationService likeApplicationService;

    @Autowired
    private FailingProductRepository failingProductRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private long brandId;

    private long stockedProductId;

    private long soldOutProductId;

    private long otherProductId;

    private OrderResult pastOrder;

    @BeforeEach
    void prepare() {
        userJpaRepository.save(new UserJpaEntity(1));
        brandId = brandApplicationService.create("삭제 대상 브랜드").id().value();
        long otherBrandId = brandApplicationService.create("다른 브랜드").id().value();
        stockedProductId = productApplicationService.create(brandId, "재고 있는 상품", 3000, 5).id();
        soldOutProductId = productApplicationService.create(brandId, "재고 0 상품", 2000, 0).id();
        otherProductId = productApplicationService.create(otherBrandId, "다른 브랜드 상품", 1000, 5).id();
        pointApplicationService.charge(1, 10000);
        OrderResult draft = orderApplicationService.create(1, List.of(new OrderApplicationService.ItemRequest(stockedProductId, 1)));
        pastOrder = orderApplicationService.confirm(1, draft.id());
    }

    @AfterEach
    void clean() {
        failingProductRepository.disarm();
        jdbcTemplate.update("delete from order_items");
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("브랜드를 삭제하면 재고 0을 포함한 연결 상품을 함께 논리 삭제하고 다른 브랜드 상품과 과거 주문은 유지한다")
    void deletesBrandWithLinkedProducts() {
        brandApplicationService.delete(brandId);

        assertThat(brandApplicationService.getAdminBrand(brandId).deleted()).isTrue();
        assertThat(productApplicationService.getAdminProduct(stockedProductId).deleted()).isTrue();
        assertThat(productApplicationService.getAdminProduct(soldOutProductId).deleted()).isTrue();
        assertThat(productApplicationService.getAdminProduct(otherProductId).deleted()).isFalse();
        assertThat(orderApplicationService.getMyOrder(1, pastOrder.id())).isEqualTo(pastOrder);
    }

    @Test
    @DisplayName("연결 상품이 없는 브랜드와 이미 삭제된 브랜드의 삭제는 성공하고 다른 대상을 바꾸지 않는다")
    void deletesBrandWithoutProductsAndRepeatedly() {
        long emptyBrandId = brandApplicationService.create("상품 없는 브랜드").id().value();

        brandApplicationService.delete(emptyBrandId);
        brandApplicationService.delete(emptyBrandId);

        assertThat(brandApplicationService.getAdminBrand(emptyBrandId).deleted()).isTrue();
        assertThat(productApplicationService.getAdminProduct(stockedProductId).deleted()).isFalse();
        assertThat(productApplicationService.getAdminProduct(otherProductId).deleted()).isFalse();
    }

    @Test
    @DisplayName("없는 브랜드 삭제는 없는 대상 오류로 거절한다")
    void rejectsMissingBrand() {
        assertThatThrownBy(() -> brandApplicationService.delete(999_999L)).isInstanceOf(BrandNotFoundException.class);
    }

    @Test
    @DisplayName("첫 상품 삭제가 DB에 반영된 뒤 다음 상품 저장에서 실패하면 브랜드와 모든 상품의 삭제가 취소된다")
    void rollsBackWhenSecondProductSaveFails() {
        failingProductRepository.failOnSave(2);

        assertThatThrownBy(() -> brandApplicationService.delete(brandId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(FailingProductRepository.INJECTED_FAILURE);

        assertThat(failingProductRepository.sawFlushedDeletionBeforeFailure()).isTrue();
        assertThat(brandApplicationService.getAdminBrand(brandId).deleted()).isFalse();
        assertThat(productApplicationService.getAdminProduct(stockedProductId).deleted()).isFalse();
        assertThat(productApplicationService.getAdminProduct(soldOutProductId).deleted()).isFalse();
        assertThat(productApplicationService.getAdminProduct(otherProductId).deleted()).isFalse();
        assertThat(orderApplicationService.getMyOrder(1, pastOrder.id())).isEqualTo(pastOrder);
    }

    @Test
    @DisplayName("일괄 삭제된 상품은 고객 조회·좋아요·주문·확정과 관리자 변경에서 제외되고 자기 좋아요 취소와 과거 주문 조회는 유지된다")
    void restrictsDeletedProductsAfterBrandDeletion() {
        likeApplicationService.register(1, stockedProductId);
        likeApplicationService.register(1, otherProductId);
        OrderResult draftBeforeDeletion = orderApplicationService.create(1,
            List.of(new OrderApplicationService.ItemRequest(stockedProductId, 1)));

        brandApplicationService.delete(brandId);

        List<Long> searchedIds = productApplicationService.search(null, 0, 20, "latest").stream()
            .map(CustomerProductResult::id)
            .toList();
        assertThat(searchedIds).containsExactly(otherProductId);
        assertThatThrownBy(() -> productApplicationService.getProduct(stockedProductId))
            .isInstanceOf(ProductNotFoundException.class);
        assertThat(productApplicationService.myLikes(1, 1, 0, 20)).extracting(CustomerProductResult::id)
            .containsExactly(otherProductId);
        assertThatThrownBy(() -> likeApplicationService.register(1, soldOutProductId))
            .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> orderApplicationService.create(1,
            List.of(new OrderApplicationService.ItemRequest(stockedProductId, 1))))
            .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> orderApplicationService.confirm(1, draftBeforeDeletion.id()))
            .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> productApplicationService.change(stockedProductId, "변경", 1))
            .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> productApplicationService.setStock(stockedProductId, 9))
            .isInstanceOf(RuleViolationException.class);

        likeApplicationService.cancel(1, stockedProductId);
        Integer remainingLikes = jdbcTemplate.queryForObject(
            "select count(*) from product_likes where user_id = 1 and product_id = ?", Integer.class, stockedProductId);
        assertThat(remainingLikes).isZero();
        assertThat(orderApplicationService.getMyOrder(1, draftBeforeDeletion.id()).status()).isEqualTo("DRAFT");
        assertThat(orderApplicationService.getMyOrder(1, pastOrder.id())).isEqualTo(pastOrder);
        assertThat(productApplicationService.getAdminProduct(stockedProductId).stock()).isEqualTo(4);
    }

    @TestConfiguration
    static class FailureInjectionConfig {
        @Bean
        @Primary
        FailingProductRepository failingProductRepository(ProductPersistenceAdapter productPersistenceAdapter,
            EntityManager entityManager, JdbcTemplate jdbcTemplate) {
            return new FailingProductRepository(productPersistenceAdapter, entityManager, jdbcTemplate);
        }
    }

    // 실제 어댑터에 위임하다가 지정한 순번의 저장에서 앞선 변경을 DB로 보낸 뒤 실패를 일으키는 테스트 전용 저장소.
    static class FailingProductRepository implements ProductRepository {
        static final String INJECTED_FAILURE = "주입된 저장 실패";

        private final ProductRepository delegate;
        private final EntityManager entityManager;
        private final JdbcTemplate jdbcTemplate;
        private final AtomicInteger failOnSave = new AtomicInteger();
        private final AtomicInteger saveCount = new AtomicInteger();
        private final AtomicBoolean sawFlushedDeletion = new AtomicBoolean();

        FailingProductRepository(ProductRepository delegate, EntityManager entityManager, JdbcTemplate jdbcTemplate) {
            this.delegate = delegate;
            this.entityManager = entityManager;
            this.jdbcTemplate = jdbcTemplate;
        }

        void failOnSave(int saveNumber) {
            saveCount.set(0);
            sawFlushedDeletion.set(false);
            failOnSave.set(saveNumber);
        }

        void disarm() {
            failOnSave.set(0);
        }

        boolean sawFlushedDeletionBeforeFailure() {
            return sawFlushedDeletion.get();
        }

        @Override
        public Product save(Product product) {
            if (failOnSave.get() > 0 && saveCount.incrementAndGet() == failOnSave.get()) {
                entityManager.flush();
                Integer deletedRows = jdbcTemplate.queryForObject(
                    "select count(*) from products where deleted = true", Integer.class);
                sawFlushedDeletion.set(deletedRows != null && deletedRows > 0);
                throw new IllegalStateException(INJECTED_FAILURE);
            }
            return delegate.save(product);
        }

        @Override
        public List<Product> findPage(int page, int size) {
            return delegate.findPage(page, size);
        }

        @Override
        public List<Product> search(Long brandId, int page, int size, String sort) {
            return delegate.search(brandId, page, size, sort);
        }

        @Override
        public List<Product> findAllByIds(Collection<ProductId> ids) {
            return delegate.findAllByIds(ids);
        }

        @Override
        public Optional<Product> findById(ProductId id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Product> findByIdForUpdate(ProductId id) {
            return delegate.findByIdForUpdate(id);
        }

        @Override
        public List<Product> findActiveByBrandIdForUpdate(BrandId brandId) {
            return delegate.findActiveByBrandIdForUpdate(brandId);
        }
    }
}
