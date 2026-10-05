package com.loopers.application.brand;

import com.loopers.application.brand.query.BrandReader;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.application.order.query.OrderReader;
import com.loopers.application.order.query.OrderView;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.query.ProductReader;
import com.loopers.application.product.query.ProductSort;
import com.loopers.application.productlike.ProductLikeFacade;
import com.loopers.application.productlike.query.ProductLikeReader;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.Fixtures;
import com.loopers.support.paging.PageQuery;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.ZonedDateTime;
import java.util.List;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class BrandFacadeIntegrationTest {

    @Autowired
    private BrandFacade brandFacade;
    @Autowired
    private BrandReader brandReader;
    @Autowired
    private ProductFacade productFacade;
    @Autowired
    private ProductReader productReader;
    @Autowired
    private ProductLikeFacade productLikeFacade;
    @Autowired
    private ProductLikeReader productLikeReader;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderReader orderReader;
    /** 중간 실패 재현용. 그 밖의 테스트에서는 실제 메서드를 그대로 부른다. */
    @MockitoSpyBean
    private BrandService brandService;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private UserModel admin;

    @BeforeEach
    void setUp() {
        user = fixtures.user();
        admin = fixtures.admin();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("FR-ADMIN-BRAND-02 브랜드 생성")
    class CreateBrand {
        @DisplayName("[FR-ADMIN-BRAND-02] 생성되고 상태는 삭제되지 않음. 이후 고객 조회에 즉시 반영 (ASM-21).")
        @Test
        void createsBrand() {
            BrandInfo created = brandFacade.createBrand(admin.getId(), "아디다스");

            assertThat(created.id()).isNotNull();
            assertThat(created.deleted()).isFalse();
            assertThat(brandReader.getBrand(user.getId(), created.id()).name()).isEqualTo("아디다스");
        }

        @DisplayName("[FR-ADMIN-BRAND-02 INVALID_BRAND][INV-14] 이름이 유효하지 않으면 생성되지 않는다.")
        @Test
        void throwsInvalidBrand() {
            assertThrowsErrorType(() -> brandFacade.createBrand(admin.getId(), " "), ErrorType.INVALID_BRAND);

            assertThat(brandReader.listBrandsForAdmin(admin.getId(), PageQuery.of(0, 10)).totalCount()).isZero();
        }

        @DisplayName("[FR-ADMIN-BRAND-02 NOT_ADMIN] 관리자가 아니면 생성되지 않는다.")
        @Test
        void throwsNotAdmin() {
            assertThrowsErrorType(() -> brandFacade.createBrand(user.getId(), "아디다스"), ErrorType.NOT_ADMIN);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-BRAND-04 브랜드 수정")
    class UpdateBrand {
        @DisplayName("[FR-ADMIN-BRAND-04] 이름이 갱신되고 고객 조회에 즉시 반영된다.")
        @Test
        void updatesName() {
            BrandModel brand = fixtures.brand("옛 이름");

            BrandInfo updated = brandFacade.updateBrand(admin.getId(), brand.getId(), "새 이름");

            assertThat(updated.name()).isEqualTo("새 이름");
            assertThat(brandReader.getBrand(user.getId(), brand.getId()).name()).isEqualTo("새 이름");
        }

        @DisplayName("[FR-ADMIN-BRAND-04 BRAND_NOT_FOUND] 존재하지 않으면 거절.")
        @Test
        void throwsBrandNotFound_whenMissing() {
            assertThrowsErrorType(() -> brandFacade.updateBrand(admin.getId(), 999L, "새 이름"), ErrorType.BRAND_NOT_FOUND);
        }

        @DisplayName("[FR-ADMIN-BRAND-04 BRAND_NOT_FOUND] 삭제된 브랜드는 수정 대상이 아니다. 삭제됨 유지.")
        @Test
        void throwsBrandNotFound_whenDeleted() {
            BrandModel deleted = fixtures.deletedBrand("삭제됨");

            assertThrowsErrorType(() -> brandFacade.updateBrand(admin.getId(), deleted.getId(), "새 이름"), ErrorType.BRAND_NOT_FOUND);

            BrandModel reloaded = fixtures.reloadBrand(deleted.getId());
            assertThat(reloaded.isDeleted()).isTrue();
            assertThat(reloaded.getName()).isEqualTo("삭제됨");
        }

        @DisplayName("[FR-ADMIN-BRAND-04 INVALID_BRAND] 정보가 유효하지 않으면 기존 값 유지.")
        @Test
        void throwsInvalidBrand_keepsOldName() {
            BrandModel brand = fixtures.brand("옛 이름");

            assertThrowsErrorType(() -> brandFacade.updateBrand(admin.getId(), brand.getId(), ""), ErrorType.INVALID_BRAND);

            assertThat(fixtures.reloadBrand(brand.getId()).getName()).isEqualTo("옛 이름");
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-BRAND-05 브랜드 삭제")
    class DeleteBrand {
        @DisplayName("[FR-ADMIN-BRAND-05][ST-01] 삭제되면 고객 조회에서 제외되고 관리자 조회에는 삭제됨으로 남는다.")
        @Test
        void deletesBrand() {
            BrandModel brand = fixtures.brand("브랜드");

            brandFacade.deleteBrand(admin.getId(), brand.getId());

            assertThrowsErrorType(() -> brandReader.getBrand(user.getId(), brand.getId()), ErrorType.BRAND_NOT_FOUND);
            assertThat(brandReader.getBrandForAdmin(admin.getId(), brand.getId()).deleted()).isTrue();
        }

        @DisplayName("[FR-ADMIN-BRAND-05][INV-10][ST-02] 삭제되지 않은 소속 상품(재고 0 포함)이 함께 삭제된다.")
        @Test
        void deletesActiveProductsTogether() {
            BrandModel brand = fixtures.brand("브랜드");
            ProductModel inStock = fixtures.product(brand.getId(), "재고 있는 상품", 1000L, 5);
            ProductModel soldOut = fixtures.product(brand.getId(), "재고 없는 상품", 1000L, 0);

            brandFacade.deleteBrand(admin.getId(), brand.getId());

            assertThat(fixtures.reloadBrand(brand.getId()).isDeleted()).isTrue();
            assertThat(fixtures.reloadProduct(inStock.getId()).isDeleted()).isTrue();
            assertThat(fixtures.reloadProduct(soldOut.getId()).isDeleted()).isTrue();
        }

        @DisplayName("[FR-ADMIN-BRAND-05][INV-10] 이미 삭제된 소속 상품은 삭제 시각이 바뀌지 않는다.")
        @Test
        void keepsAlreadyDeletedProducts() {
            BrandModel brand = fixtures.brand("브랜드");
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제된 상품", 1000L, 0);
            ZonedDateTime deletedAt = fixtures.reloadProduct(deleted.getId()).getDeletedAt();

            brandFacade.deleteBrand(admin.getId(), brand.getId());

            assertThat(fixtures.reloadBrand(brand.getId()).isDeleted()).isTrue();
            assertThat(fixtures.reloadProduct(deleted.getId()).getDeletedAt()).isEqualTo(deletedAt);
        }

        @DisplayName("[FR-ADMIN-BRAND-05] 다른 브랜드와 그 상품은 바뀌지 않는다.")
        @Test
        void doesNotTouchOtherBrands() {
            BrandModel target = fixtures.brand("대상");
            fixtures.product(target.getId(), "대상 상품", 1000L, 1);
            BrandModel other = fixtures.brand("다른 브랜드");
            ProductModel otherProduct = fixtures.product(other.getId(), "다른 상품", 1000L, 1);

            brandFacade.deleteBrand(admin.getId(), target.getId());

            assertThat(fixtures.reloadBrand(other.getId()).isDeleted()).isFalse();
            assertThat(fixtures.reloadProduct(otherProduct.getId()).isDeleted()).isFalse();
        }

        @DisplayName("[FR-ADMIN-BRAND-05] 상품을 삭제한 뒤 중간에 실패하면 브랜드·상품 변경이 전부 취소된다.")
        @Test
        void rollsBackAll_whenFailsMidway() {
            BrandModel brand = fixtures.brand("브랜드");
            ProductModel first = fixtures.product(brand.getId(), "상품1", 1000L, 1);
            ProductModel second = fixtures.product(brand.getId(), "상품2", 1000L, 0);
            doThrow(new IllegalStateException("중간 실패")).when(brandService).delete(brand.getId());

            assertThatThrownBy(() -> brandFacade.deleteBrand(admin.getId(), brand.getId()))
                .isInstanceOf(IllegalStateException.class);

            assertThat(fixtures.reloadBrand(brand.getId()).isDeleted()).isFalse();
            assertThat(fixtures.reloadProduct(first.getId()).isDeleted()).isFalse();
            assertThat(fixtures.reloadProduct(second.getId()).isDeleted()).isFalse();
        }

        @DisplayName("[FR-ADMIN-BRAND-05 NOT_ADMIN] 관리자가 아니면 거절하고 브랜드·상품은 그대로.")
        @Test
        void throwsNotAdmin() {
            BrandModel brand = fixtures.brand("브랜드");
            ProductModel product = fixtures.product(brand.getId(), "상품", 1000L, 1);

            assertThrowsErrorType(() -> brandFacade.deleteBrand(user.getId(), brand.getId()), ErrorType.NOT_ADMIN);

            assertThat(fixtures.reloadBrand(brand.getId()).isDeleted()).isFalse();
            assertThat(fixtures.reloadProduct(product.getId()).isDeleted()).isFalse();
        }

        @DisplayName("[FR-ADMIN-BRAND-05 BRAND_NOT_FOUND] 존재하지 않으면 거절.")
        @Test
        void throwsBrandNotFound_whenMissing() {
            assertThrowsErrorType(() -> brandFacade.deleteBrand(admin.getId(), 999L), ErrorType.BRAND_NOT_FOUND);
        }

        @DisplayName("[FR-ADMIN-BRAND-05 BRAND_NOT_FOUND] 이미 삭제된 브랜드의 재삭제는 거절 (ASM-18).")
        @Test
        void throwsBrandNotFound_whenAlreadyDeleted() {
            BrandModel deleted = fixtures.deletedBrand("삭제됨");

            assertThrowsErrorType(() -> brandFacade.deleteBrand(admin.getId(), deleted.getId()), ErrorType.BRAND_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-BRAND-05 브랜드 삭제 후 연결")
    class AfterBrandDeleted {
        private UserModel buyer;
        private ProductModel product;
        private Long confirmedOrderId;
        private Long draftOrderId;

        @BeforeEach
        void deleteBrandWithProduct() {
            buyer = fixtures.userWithBalance(10_000L);
            BrandModel brand = fixtures.brand("브랜드");
            product = fixtures.product(brand.getId(), "상품", 1_000L, 10);
            productLikeFacade.like(buyer.getId(), product.getId());
            confirmedOrderId = orderFacade.createOrder(buyer.getId(), List.of(new OrderItemCommand(product.getId(), 2))).id();
            orderFacade.confirmOrder(buyer.getId(), confirmedOrderId);
            draftOrderId = orderFacade.createOrder(buyer.getId(), List.of(new OrderItemCommand(product.getId(), 1))).id();

            brandFacade.deleteBrand(admin.getId(), brand.getId());
        }

        @DisplayName("[FR-PRODUCT-01/02] 고객 상품 목록·상세에서 제외된다.")
        @Test
        void excludedFromCustomerProducts() {
            assertThat(productReader.listProducts(buyer.getId(), ProductSort.LATEST, PageQuery.of(0, 10)).items()).isEmpty();
            assertThrowsErrorType(() -> productReader.getProduct(buyer.getId(), product.getId()), ErrorType.PRODUCT_NOT_FOUND);
        }

        @DisplayName("[FR-LIKE-01/03] 새 좋아요는 거절되고 내 좋아요 목록에서 제외된다.")
        @Test
        void excludedFromLikes() {
            assertThrowsErrorType(() -> productLikeFacade.like(user.getId(), product.getId()), ErrorType.PRODUCT_NOT_FOUND);
            assertThat(productLikeReader.listMyLikes(buyer.getId(), buyer.getId(), PageQuery.of(0, 10)).items()).isEmpty();
        }

        @DisplayName("[FR-LIKE-02] 남아 있는 자기 좋아요는 취소할 수 있다 (ASM-07).")
        @Test
        void canUnlike() {
            assertThat(productReader.getProductForAdmin(admin.getId(), product.getId()).likeCount()).isEqualTo(1);

            productLikeFacade.unlike(buyer.getId(), product.getId());

            assertThat(productReader.getProductForAdmin(admin.getId(), product.getId()).likeCount()).isZero();
        }

        @DisplayName("[FR-ORDER-01] 새 주문은 거절된다.")
        @Test
        void rejectsNewOrder() {
            assertThrowsErrorType(() -> orderFacade.createOrder(buyer.getId(), List.of(new OrderItemCommand(product.getId(), 1))),
                ErrorType.PRODUCT_NOT_FOUND);
        }

        @DisplayName("[FR-ORDER-02][ASM-22] 삭제 전에 만든 DRAFT 주문의 확정은 거절되고 주문·재고·잔액이 그대로다.")
        @Test
        void rejectsConfirmOfDraftOrder() {
            long balance = fixtures.balanceOf(buyer.getId());

            assertThrowsErrorType(() -> orderFacade.confirmOrder(buyer.getId(), draftOrderId), ErrorType.PRODUCT_NOT_FOUND);

            assertThat(fixtures.reloadOrder(draftOrderId).getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(fixtures.reloadProduct(product.getId()).getStock()).isEqualTo(8);
            assertThat(fixtures.balanceOf(buyer.getId())).isEqualTo(balance);
        }

        @DisplayName("[FR-ORDER-04][ASM-10] 확정된 주문의 품목·수량·단가·총액·결제액이 그대로 조회된다.")
        @Test
        void keepsConfirmedOrder() {
            OrderView.Detail order = orderReader.getMyOrder(buyer.getId(), confirmedOrderId);

            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED.name());
            assertThat(order.totalAmount()).isEqualTo(2_000L);
            assertThat(order.paidAmount()).isEqualTo(2_000L);
            assertThat(order.items()).singleElement().satisfies(item -> {
                assertThat(item.productId()).isEqualTo(product.getId());
                assertThat(item.quantity()).isEqualTo(2);
                assertThat(item.unitPrice()).isEqualTo(1_000L);
                assertThat(item.lineAmount()).isEqualTo(2_000L);
            });
        }

        @DisplayName("[FR-ADMIN-PRODUCT-04/06] 삭제된 상품의 수정·재고 변경은 거절된다.")
        @Test
        void rejectsUpdateAndStockChange() {
            assertThrowsErrorType(() -> productFacade.updateProduct(admin.getId(), product.getId(), "새 이름", 2_000L), ErrorType.PRODUCT_NOT_FOUND);
            assertThrowsErrorType(() -> productFacade.updateStock(admin.getId(), product.getId(), 100), ErrorType.PRODUCT_NOT_FOUND);
        }

        @DisplayName("[FR-ADMIN-PRODUCT-03][ASM-15] 관리자 상품 조회에는 삭제됨으로 남는다.")
        @Test
        void remainsInAdminProducts() {
            assertThat(productReader.getProductForAdmin(admin.getId(), product.getId()).deleted()).isTrue();
        }
    }
}
