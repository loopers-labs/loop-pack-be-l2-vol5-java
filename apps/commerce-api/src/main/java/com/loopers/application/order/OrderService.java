package com.loopers.application.order;

import com.loopers.application.product.ProductQueryException;
import com.loopers.application.user.AdminAuthorization;
import com.loopers.application.user.UserResolutionException;
import com.loopers.application.user.UserResolver;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.domain.user.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class OrderService {
    private final UserResolver userResolver;
    private final UserRepository users;
    private final BrandRepository brands;
    private final ProductRepository products;
    private final OrderRepository orders;

    public OrderService(UserResolver userResolver, UserRepository users, BrandRepository brands,
                        ProductRepository products, OrderRepository orders) {
        this.userResolver = userResolver;
        this.users = users;
        this.brands = brands;
        this.products = products;
        this.orders = orders;
    }

    public OrderInfo create(String requester, List<OrderQuantities.Item> inputs) {
        Map<Long, Integer> quantities = OrderQuantities.combine(inputs).byProductId();
        long userId = userResolver.resolve(requester).userId();
        User user = users.lockById(userId).orElseThrow(UserResolutionException::userNotFound);
        Map<Long, Product> locked = lockProducts(quantities.keySet());
        List<OrderItem> items = locked.entrySet().stream()
            .map(entry -> new OrderItem(entry.getValue(), quantities.get(entry.getKey()))).toList();
        return OrderInfo.from(orders.save(Order.create(user, items)));
    }

    public OrderInfo confirm(String requester, long orderId) {
        long userId = userResolver.resolve(requester).userId();
        Order order = orders.lockById(orderId).orElseThrow(this::orderNotFound);
        order.requireOwner(userId);
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            return OrderInfo.from(order);
        }
        order.validateStoredQuantities();
        User user = users.lockById(userId).orElseThrow(UserResolutionException::userNotFound);
        Map<Long, Integer> quantities = order.getItems().stream()
            .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity));
        Map<Long, Product> locked = lockProducts(quantities.keySet());
        locked.forEach((id, product) -> product.validateStockDeduction(quantities.get(id)));
        user.validateDeduction(order.getTotalAmount());
        locked.forEach((id, product) -> product.deductStock(quantities.get(id)));
        user.deduct(order.getTotalAmount());
        order.confirm(ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        return OrderInfo.from(order);
    }

    @Transactional(readOnly = true)
    public OrderInfo getDetail(String requester, long orderId) {
        long userId = existingUserId(requester);
        Order order = orders.findById(orderId).orElseThrow(this::orderNotFound);
        order.requireOwner(userId);
        return OrderInfo.from(order);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderInfo> getPage(String requester, int page, int size) {
        long userId = existingUserId(requester);
        return orders.findPage(userId, null, page, size).map(OrderInfo::from);
    }

    @Transactional(readOnly = true)
    public AdminOrderInfo adminDetail(UserRole requester, long orderId) {
        AdminAuthorization.requireAdmin(requester);
        return adminInfo(orders.findById(orderId).orElseThrow(this::orderNotFound));
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderInfo> adminPage(UserRole requester, String userId, OrderStatus status, int page, int size) {
        AdminAuthorization.requireAdmin(requester);
        Long buyerId = null;
        if (userId != null) {
            try {
                buyerId = userResolver.resolve(userId).userId();
            } catch (UserResolutionException exception) {
                if (exception.getReason() == UserResolutionException.Reason.USER_NOT_FOUND) {
                    return PageResult.of(List.of(), page, size, 0);
                }
                throw exception;
            }
        }
        return orders.findPage(buyerId, status, page, size).map(this::adminInfo);
    }

    private AdminOrderInfo adminInfo(Order order) {
        String externalId = userResolver.findExternalId(order.getUserId())
            .orElseThrow(() -> new IllegalStateException("Stored buyer has no external identity"));
        return AdminOrderInfo.from(OrderInfo.from(order), externalId);
    }

    private long existingUserId(String requester) {
        long userId = userResolver.resolve(requester).userId();
        users.findById(userId).orElseThrow(UserResolutionException::userNotFound);
        return userId;
    }

    private Map<Long, Product> lockProducts(Set<Long> productIds) {
        TreeSet<Long> brandIds = new TreeSet<>();
        for (long productId : new TreeSet<>(productIds)) {
            brandIds.add(products.findBrandId(productId).orElseThrow(this::productNotFound));
        }
        for (long brandId : brandIds) {
            Brand brand = brands.lockById(brandId).orElseThrow(this::productNotFound);
            if (brand.isDeleted()) {
                throw productNotFound();
            }
        }
        Map<Long, Product> locked = new TreeMap<>();
        for (long productId : new TreeSet<>(productIds)) {
            Product product = products.lockById(productId).orElseThrow(this::productNotFound);
            if (product.isDeleted()) {
                throw productNotFound();
            }
            locked.put(productId, product);
        }
        return locked;
    }

    private ProductQueryException productNotFound() {
        return new ProductQueryException(ProductQueryException.Reason.PRODUCT_NOT_FOUND);
    }

    private OrderException orderNotFound() {
        return new OrderException(OrderException.Reason.ORDER_NOT_FOUND);
    }
}
