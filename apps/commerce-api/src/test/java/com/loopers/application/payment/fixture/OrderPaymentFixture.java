package com.loopers.application.payment.fixture;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.application.payment.RequestOrderPaymentFacade;
import com.loopers.application.point.fixture.PointFixture;
import com.loopers.config.FixtureMonkeyConfig;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Transactional
public class OrderPaymentFixture {
    @Autowired private UserFixture users;
    @Autowired private BrandFixture brands;
    @Autowired private ProductFixture products;
    @Autowired private PointFixture points;
    @Autowired private OrderFixture orders;
    @Autowired private JdbcTemplate jdbc;

    public PreparedOrder createUnpaidOrder() {
        long userId = createBuyer();
        var product = createProductWithStock(3_500, 5);
        int orderQuantity = 2;
        var order = orders.createOrder(userId, product, orderQuantity);
        return new PreparedOrder(userId, order.getId(), product.getId(), product.getStock(), orderQuantity);
    }

    public long createBuyer() {
        return users.createUsers(1).getFirst();
    }

    public long createBuyerWithPoints(long pointBalance) {
        long userId = createBuyer();
        points.createBalance(userId, pointBalance);
        return userId;
    }

    public Product createProductWithStock(long unitPrice, int availableStock) {
        var brand = brands.createBrand();
        return products.createProduct(brand.getId(), "주문 상품", unitPrice, availableStock);
    }

    public RequestOrderPaymentFacade.Command createPaymentRequestCommand() {
        return FixtureMonkeyConfig.create(42L)
                .giveMeBuilder(RequestOrderPaymentFacade.Command.class)
                .set("paymentAttemptKey", UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"))
                .set("cardType", "TEST")
                .set("cardNo", "0000-0000-0000-0000")
                .sample();
    }

    @Transactional(readOnly = true)
    public StoredPayment findPaymentByOrderId(long orderId) {
        return jdbc.queryForObject(
                "select payment_attempt_key, provider_order_id, amount, status, transaction_key"
                        + " from payments where order_id = ?",
                (row, rowNumber) -> new StoredPayment(
                        UUID.fromString(row.getString("payment_attempt_key")),
                        row.getString("provider_order_id"),
                        row.getLong("amount"),
                        PaymentStatus.valueOf(row.getString("status")),
                        row.getString("transaction_key")),
                orderId);
    }

    @Transactional(readOnly = true)
    public StoredReservation findReservationByOrderAndProduct(long orderId, long productId) {
        return jdbc.queryForObject(
                "select quantity, status from stock_reservations where order_id = ? and product_id = ?",
                (row, rowNumber) -> new StoredReservation(row.getInt("quantity"),
                        StockReservationStatus.valueOf(row.getString("status"))),
                orderId, productId);
    }

    public record PreparedOrder(long userId, long orderId, long productId, int stockBeforePayment, int orderQuantity) {}

    public record StoredPayment(
            UUID paymentAttemptKey, String providerOrderId, long amount, PaymentStatus status, String transactionKey) {}

    public record StoredReservation(int quantity, StockReservationStatus status) {}
}
