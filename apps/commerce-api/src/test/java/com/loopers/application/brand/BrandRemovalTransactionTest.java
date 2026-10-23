package com.loopers.application.brand;

import com.loopers.application.order.AdminOrderFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.jpa.FailingStatementInspector;
import com.loopers.support.jpa.FailingStatementInspector.InjectedFailureException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 브랜드 일괄 삭제가 하나의 트랜잭션으로 반영되거나(성공) 전부 취소되는지(실패) 실제 MySQL 로 확인한다.
 *
 * <p>테스트를 부모 트랜잭션으로 감싸지 않는다. 데이터는 각각 commit 해 두고, 삭제 뒤의 상태는 영속성 컨텍스트를 거치지
 * 않는 {@link JdbcTemplate} 로 새로 읽는다. 실패는 {@link FailingStatementInspector} 로 상품 UPDATE 문이 DB 로
 * 나가기 직전에 주입하며, 운영 코드에는 실패용 스위치가 없다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
    + "com.loopers.support.jpa.FailingStatementInspector")
class BrandRemovalTransactionTest {

    @Autowired
    private AdminBrandFacade adminBrandFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private AdminOrderFacade adminOrderFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        FailingStatementInspector.disarm();
        databaseCleanUp.truncateAllTables();
    }

    /** 삭제 대상 브랜드 A 와 그 상품, 이미 삭제된 상품, 다른 브랜드 B 와 과거 주문을 commit 해 둔다. */
    private class Fixture {
        final Brand brandA = brandRepository.save(Brand.create("루퍼스"));
        final Brand brandB = brandRepository.save(Brand.create("다른 브랜드"));
        final Product inStock = productRepository.save(Product.create(brandA.getId(), "러닝화", new Money(89_000), new Stock(10)));
        final Product soldOut = productRepository.save(Product.create(brandA.getId(), "품절 상품", new Money(30_000), new Stock(0)));
        final Product alreadyDeleted = saveDeletedProduct(brandA.getId());
        final Product otherBrandProduct = productRepository.save(Product.create(brandB.getId(), "양말", new Money(5_000), new Stock(3)));
        final OrderInfo pastOrder;

        Fixture() {
            User user = userRepository.save(User.create());
            pastOrder = orderFacade.create(user.getId(), List.of(new OrderItemCommand(inStock.getId(), 2)));
        }

        private Product saveDeletedProduct(Long brandId) {
            Product product = Product.create(brandId, "이미 삭제됨", new Money(1_000), new Stock(1));
            product.delete();
            return productRepository.save(product);
        }
    }

    private boolean  isDeleted(String table, Long id) {
        return jdbcTemplate.queryForObject("select deleted_at is not null from " + table + " where id = ?", Boolean.class, id);
    }

    private Object deletedAtOf(String table, Long id) {
        return jdbcTemplate.queryForObject("select deleted_at from " + table + " where id = ?", Object.class, id);
    }

    private void assertUntouched(Fixture fixture) {
        assertThat(isDeleted("brands", fixture.brandB.getId())).isFalse();
        assertThat(isDeleted("products", fixture.otherBrandProduct.getId())).isFalse();
        OrderInfo order = adminOrderFacade.get(fixture.pastOrder.id());
        assertThat(order.items()).hasSize(1);
        assertThat(order.items().get(0).productName()).isEqualTo("러닝화");
        assertThat(order.items().get(0).unitPrice()).isEqualTo(89_000L);
    }

    @DisplayName("브랜드를 삭제할 때,")
    @Nested
    class Remove {
        @DisplayName("브랜드와 연결된 미삭제 상품(재고 0 포함)이 모두 삭제되고, 이미 삭제된 상품·다른 브랜드·과거 주문은 그대로다.")
        @Test
        void removesBrandAndLinkedProducts_andKeepsEverythingElse() {
            // arrange
            Fixture fixture = new Fixture();
            Object alreadyDeletedAt = deletedAtOf("products", fixture.alreadyDeleted.getId());

            // act
            adminBrandFacade.delete(fixture.brandA.getId());

            // assert
            assertThat(isDeleted("brands", fixture.brandA.getId())).isTrue();
            assertThat(isDeleted("products", fixture.inStock.getId())).isTrue();
            assertThat(isDeleted("products", fixture.soldOut.getId())).isTrue();
            assertThat(deletedAtOf("products", fixture.alreadyDeleted.getId())).isEqualTo(alreadyDeletedAt);
            assertUntouched(fixture);
        }

        @DisplayName("연결된 상품이 없으면 브랜드만 삭제한다.")
        @Test
        void removesOnlyBrand_whenNoLinkedProducts() {
            // arrange
            Brand empty = brandRepository.save(Brand.create("빈 브랜드"));
            Brand other = brandRepository.save(Brand.create("다른 브랜드"));
            Product otherProduct = productRepository.save(Product.create(other.getId(), "양말", new Money(5_000), new Stock(3)));

            // act
            adminBrandFacade.delete(empty.getId());

            // assert
            assertThat(isDeleted("brands", empty.getId())).isTrue();
            assertThat(isDeleted("brands", other.getId())).isFalse();
            assertThat(isDeleted("products", otherProduct.getId())).isFalse();
        }

        @DisplayName("중간에 실패하면 브랜드와 모든 상품의 변경이 취소되고, 다른 대상과 과거 주문은 그대로다.")
        @Test
        void rollsBackEverything_whenProductUpdateFails() {
            // arrange
            Fixture fixture = new Fixture();
            FailingStatementInspector.arm("update products", 1);

            // act & assert: 실패는 밖으로 전달된다. Spring 이 감쌀 수 있으므로 원인 사슬에서 주입한 예외를 찾는다.
            assertThatThrownBy(() -> adminBrandFacade.delete(fixture.brandA.getId()))
                .hasRootCauseInstanceOf(InjectedFailureException.class);

            // 실패 전에 브랜드 변경이 실제로 DB 로 나갔어야 rollback 검증이 의미를 가진다.
            List<String> mutations = FailingStatementInspector.mutations();
            assertThat(mutations).hasSizeGreaterThanOrEqualTo(2);
            assertThat(mutations.get(mutations.size() - 1)).startsWith("update products");
            assertThat(mutations.subList(0, mutations.size() - 1)).anyMatch(sql -> sql.startsWith("update brands"));

            FailingStatementInspector.disarm();
            assertThat(isDeleted("brands", fixture.brandA.getId())).isFalse();
            assertThat(isDeleted("products", fixture.inStock.getId())).isFalse();
            assertThat(isDeleted("products", fixture.soldOut.getId())).isFalse();
            assertUntouched(fixture);
        }
    }
}
