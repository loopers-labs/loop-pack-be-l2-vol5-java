package com.loopers.interfaces.api.product;

import com.loopers.domain.common.PageWindow;
import com.loopers.domain.product.ProductAdminQuery;

import java.util.List;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductName;

public class ProductAdminV1Dto {

    public record RegisterRequest(Long brandId, String name, Long price) {
        public RegisterRequest {
            if (brandId == null) {
                throw new IllegalArgumentException("brandId 는 필수입니다.");
            }
            ProductName.of(name);
            if (price == null) {
                throw new IllegalArgumentException("price 는 필수입니다.");
            }
            Price.of(price);
        }

        public Price toPrice() {
            return Price.of(price);
        }
    }

    public record UpdateRequest(String name, Long price) {
        public UpdateRequest {
            ProductName.of(name);
            if (price == null) {
                throw new IllegalArgumentException("price 는 필수입니다.");
            }
            Price.of(price);
        }

        public Price toPrice() {
            return Price.of(price);
        }
    }

    public record StockRequest(Integer quantity) {
        public StockRequest {
            if (quantity == null) {
                throw new IllegalArgumentException("quantity 는 필수입니다.");
            }
            Quantity.of(quantity);
        }

        public Quantity toQuantity() {
            return Quantity.of(quantity);
        }
    }

    public record AdminProductListItem(
        Long id, Long brandId, String brandName, String name, long price, int quantity) {
        static AdminProductListItem from(ProductAdminQuery.View view) {
            return new AdminProductListItem(
                view.id(), view.brandId(), view.brandName(), view.name(), view.price(), view.quantity());
        }
    }

    public record AdminProductPageResponse(
        List<AdminProductListItem> items, int page, int size, boolean hasNext) {
        public static AdminProductPageResponse of(PageWindow<ProductAdminQuery.View> productPage, int page, int size) {
            return new AdminProductPageResponse(
                productPage.items().stream().map(AdminProductListItem::from).toList(),
                page, size, productPage.hasNext());
        }
    }

    public record AdminProductResponse(Long id, Long brandId, String name, long price, int quantity) {
        public static AdminProductResponse from(Product product) {
            return new AdminProductResponse(
                product.getId(), product.getBrandId(), product.getName(),
                product.getPrice().value(), product.getQuantity().value());
        }
    }
}
