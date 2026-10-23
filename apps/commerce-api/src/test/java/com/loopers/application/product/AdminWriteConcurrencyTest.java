package com.loopers.application.product;

import com.loopers.application.brand.AdminBrandFacade;
import com.loopers.application.payment.PaymentFlowTestSupport;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.support.concurrent.ConcurrentRequests;
import com.loopers.support.concurrent.ConcurrentRequests.Results;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자가 상품·브랜드를 바꾸는 요청과 결제 시작(재고 확보)·삭제가 같은 행에서 겹칠 때, 한쪽의 변경이 다른 쪽의 전체 컬럼
 * UPDATE에 덮이지 않는지 확인한다. 결제 시작도 확정처럼 상품 행을 잠그고 재고를 바꾸므로 보호 대상은 같다. 가격만 바꾸는 요청이 재고를 되돌리거나, 이름만 바꾸는 요청이 삭제를 되살리는 반례다.
 *
 * <p>결제 시작은 포인트를 건드리지 않으므로 모든 구매자의 잔액은 그대로여야 한다. 데이터는 요청 전에 commit 하고 테스트를
 * 부모 트랜잭션으로 감싸지 않는다. 요청은 출발만 맞춰 동시에 시작하고, 모두
 * 끝난 뒤 {@code JdbcTemplate} 으로 DB 를 새로 읽어 확인한다. 어느 쪽이 먼저 잠금을 잡는지는 정해지지 않으므로, 두 순서
 * 모두에서 성립하는 결과를 확인하고 경쟁은 확률적이라 반복 실행한다.
 */
class AdminWriteConcurrencyTest extends PaymentFlowTestSupport {

    private static final int REPETITIONS = 20;
    // 관리자 요청은 짧아서 한 개만으로는 결제 시작과 겹치기 전에 끝나는 경우가 많다. 여러 개를 함께 보내 겹칠 틈을 늘린다.
    private static final int ADMIN_REQUESTS = 4;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private AdminBrandFacade adminBrandFacade;

    @Autowired
    private BrandRepository brandRepository;

    private Product saveProduct(Long brandId, long price, int stock) {
        return productRepository.save(Product.create(brandId, "러닝화", new Money(price), new Stock(stock)));
    }

    private boolean isDeleted(String table, Long id) {
        return jdbcTemplate.queryForObject("select deleted_at is not null from " + table + " where id = ?", Boolean.class, id);
    }

    private void assertAccountedFor(Results results, int requests) {
        assertThat(results.successes() + results.rejections() + results.technicalErrors())
            .as("성공 + 업무 거절 + 기술 오류 = 전체 요청: %s", results.outcomes())
            .isEqualTo(requests);
        assertThat(results.technicalErrors()).as("기술 오류: %s", results.outcomes()).isZero();
    }

    private record Buyers(List<User> users, List<Long> orderIds, List<Runnable> starts) {}

    // 구매자마다 같은 상품 1개짜리 주문을 만들어 둔다. 결제 시작 요청이 여러 개 줄을 서야 관리자 요청이 읽고 쓰는 사이가 벌어진다.
    private Buyers prepareBuyers(Product product, int count) {
        List<User> users = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        List<Runnable> starts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User buyer = saveUser(10_000);
            Long orderId = placeOrder(buyer, product, 1);
            users.add(buyer);
            orderIds.add(orderId);
            starts.add(() -> orderFacade.confirm(command(buyer, orderId, "attempt-" + orderId)));
        }
        return new Buyers(users, orderIds, starts);
    }

    private List<Runnable> with(List<Runnable> requests, Runnable... more) {
        List<Runnable> all = new ArrayList<>(requests);
        all.addAll(List.of(more));
        return all;
    }

    private List<Runnable> repeated(int times, Runnable request) {
        List<Runnable> all = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            all.add(request);
        }
        return all;
    }

    private List<Runnable> merge(List<Runnable> first, List<Runnable> second) {
        List<Runnable> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private long startedCount(Buyers buyers) {
        return buyers.orderIds().stream().filter(orderId -> orderStatusOf(orderId).equals("PAYMENT_PENDING")).count();
    }

    // 결제를 시작한 주문과 거절된 주문(DRAFT)이 정확히 나뉘고, 어느 구매자의 포인트도 변하지 않아야 한다.
    private void assertEachBuyerIsConsistent(Buyers buyers) {
        for (int i = 0; i < buyers.users().size(); i++) {
            assertThat(orderStatusOf(buyers.orderIds().get(i))).isIn("PAYMENT_PENDING", "DRAFT");
            assertThat(balanceOf(buyers.users().get(i))).isEqualTo(10_000L);
        }
    }

    @DisplayName("상품 이름·가격 수정 4개와 결제 시작 5개를 동시에 하면, 모두 성공하고 수정한 이름·가격과 확보된 재고가 함께 남는다.")
    @RepeatedTest(REPETITIONS)
    void keepsBothEditAndStockDeduction_whenEditingProductWhileStarting() {
        // arrange
        Brand brand = brandRepository.save(Brand.create("루퍼스"));
        Product product = saveProduct(brand.getId(), 3_000, 10);
        Buyers buyers = prepareBuyers(product, 5);

        // act
        Results results = ConcurrentRequests.run(merge(buyers.starts(),
            repeated(ADMIN_REQUESTS, () -> adminProductFacade.update(product.getId(), "새 이름", 5_000))));

        // assert: 수정이 먼저 읽은 재고(낡은 값)로 차감을 되돌리면 안 되고, 결제 시작이 수정을 되돌려도 안 된다.
        // 결제액은 주문 당시 가격이다.
        assertAccountedFor(results, 5 + ADMIN_REQUESTS);
        assertThat(results.successes()).isEqualTo(5 + ADMIN_REQUESTS);
        assertThat(stockOf(product)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForMap("select name, price from products where id = ?", product.getId()))
            .containsEntry("name", "새 이름").containsEntry("price", 5_000L);
        assertThat(startedCount(buyers)).isEqualTo(5);
        assertEachBuyerIsConsistent(buyers);
    }

    @DisplayName("재고 설정과 결제 시작 5개를 동시에 하면, 설정값에서 설정 뒤에 시작된 만큼만 차감된 값이 남는다.")
    @RepeatedTest(REPETITIONS)
    void appliesStockSettingAndDeductionsInLockOrder_whenChangingStockWhileStarting() {
        // arrange: 재고 10 을 20 으로 설정하는 요청과 1개짜리 결제 시작 5개
        Brand brand = brandRepository.save(Brand.create("루퍼스"));
        Product product = saveProduct(brand.getId(), 3_000, 10);
        Buyers buyers = prepareBuyers(product, 5);

        // act
        Results results = ConcurrentRequests.run(
            with(buyers.starts(), () -> adminProductFacade.changeStock(product.getId(), 20)));

        // assert: 설정이 잠금 순서의 어디에 오느냐에 따라 15(모든 시작이 설정 뒤) ~ 20(모든 시작이 설정 앞)이다.
        // 어느 쪽이든 시작은 모두 성공하고 재고가 설정 전 값에서 줄어든 상태(10 이하)로 남지 않는다.
        assertAccountedFor(results, 6);
        assertThat(results.successes()).isEqualTo(6);
        assertThat(startedCount(buyers)).isEqualTo(5);
        assertThat(stockOf(product)).isBetween(15, 20);
        assertEachBuyerIsConsistent(buyers);
    }

    @DisplayName("재고 설정 5개와 상품 삭제를 동시에 하면, 삭제된 상품은 되살아나지 않는다.")
    @RepeatedTest(REPETITIONS)
    void keepsProductDeleted_whenChangingStockWhileDeleting() {
        // arrange
        Brand brand = brandRepository.save(Brand.create("루퍼스"));
        Product product = saveProduct(brand.getId(), 3_000, 10);
        List<Runnable> requests = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int stock = 20 + i;
            requests.add(() -> adminProductFacade.changeStock(product.getId(), stock));
        }

        // act
        Results results = ConcurrentRequests.run(with(requests, () -> adminProductFacade.delete(product.getId())));

        // assert: 삭제 뒤에 도착한 설정은 없는 상품으로 거절되고, 낡은 행으로 쓴 UPDATE 가 삭제 표시를 지우면 안 된다.
        assertAccountedFor(results, 6);
        assertThat(results.rejections() - results.rejectedWith(ErrorType.NOT_FOUND)).isZero();
        assertThat(isDeleted("products", product.getId())).as("재고 설정의 UPDATE 가 삭제를 되살리면 안 된다").isTrue();
    }

    @DisplayName("상품 삭제 4개와 결제 시작 5개를 동시에 하면, 삭제된 상품은 되살아나지 않고 시작된 만큼만 재고가 차감된다.")
    @RepeatedTest(REPETITIONS)
    void keepsProductDeleted_whenDeletingProductWhileStarting() {
        // arrange
        Brand brand = brandRepository.save(Brand.create("루퍼스"));
        Product product = saveProduct(brand.getId(), 3_000, 10);
        Buyers buyers = prepareBuyers(product, 5);

        // act
        Results results = ConcurrentRequests.run(merge(buyers.starts(),
            repeated(ADMIN_REQUESTS, () -> adminProductFacade.delete(product.getId()))));

        // assert: 삭제가 낡은 재고로 쓰면 시작된 차감이 되돌아가고, 결제 시작이 낡은 삭제 표시로 쓰면 상품이 되살아난다.
        // 먼저 삭제한 요청 뒤에 도착한 삭제는 없는 상품으로 거절된다.
        assertAccountedFor(results, 5 + ADMIN_REQUESTS);
        assertThat(results.rejectedWith(ErrorType.NOT_FOUND)).isLessThan(ADMIN_REQUESTS);
        assertThat(isDeleted("products", product.getId())).as("결제 시작의 UPDATE 가 삭제를 되살리면 안 된다").isTrue();
        assertThat(stockOf(product)).isEqualTo(10 - (int) startedCount(buyers));
        assertThat(results.rejectedWith(ErrorType.PRODUCT_UNAVAILABLE)).isEqualTo(5 - startedCount(buyers));
        assertEachBuyerIsConsistent(buyers);
    }

    @DisplayName("브랜드 수정과 브랜드 삭제를 동시에 하면, 삭제된 브랜드는 되살아나지 않고 수정은 성공하거나 없는 브랜드로 거절된다.")
    @RepeatedTest(REPETITIONS)
    void keepsBrandDeleted_whenRenamingBrandWhileDeleting() {
        // arrange
        Brand brand = brandRepository.save(Brand.create("루퍼스"));
        Product product = saveProduct(brand.getId(), 3_000, 10);

        // act
        Results results = ConcurrentRequests.run(List.of(
            () -> adminBrandFacade.update(brand.getId(), "새 이름"),
            () -> adminBrandFacade.delete(brand.getId())
        ));

        // assert: 수정의 전체 컬럼 UPDATE 가 삭제 표시를 지우면 안 된다.
        assertAccountedFor(results, 2);
        assertThat(results.rejections() - results.rejectedWith(ErrorType.NOT_FOUND)).isZero();
        assertThat(isDeleted("brands", brand.getId())).isTrue();
        assertThat(isDeleted("products", product.getId())).isTrue();
    }

    @DisplayName("브랜드 삭제와 그 상품의 결제 시작 5개를 동시에 하면, 상품은 되살아나지 않고 시작된 만큼만 재고가 차감된다.")
    @RepeatedTest(REPETITIONS)
    void keepsProductDeleted_whenDeletingBrandWhileStarting() {
        // arrange
        Brand brand = brandRepository.save(Brand.create("루퍼스"));
        Product product = saveProduct(brand.getId(), 3_000, 10);
        Buyers buyers = prepareBuyers(product, 5);

        // act
        Results results = ConcurrentRequests.run(
            with(buyers.starts(), () -> adminBrandFacade.delete(brand.getId())));

        // assert
        assertAccountedFor(results, 6);
        assertThat(results.rejectedWith(ErrorType.NOT_FOUND)).isZero();
        assertThat(isDeleted("brands", brand.getId())).isTrue();
        assertThat(isDeleted("products", product.getId())).as("결제 시작의 UPDATE 가 삭제를 되살리면 안 된다").isTrue();
        assertThat(stockOf(product)).isEqualTo(10 - (int) startedCount(buyers));
        assertThat(results.rejectedWith(ErrorType.PRODUCT_UNAVAILABLE)).isEqualTo(5 - startedCount(buyers));
        assertEachBuyerIsConsistent(buyers);
    }
}
