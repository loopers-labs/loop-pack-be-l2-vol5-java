package com.loopers.interfaces.api.admin.product;

public class AdminProductV1Dto {
    public record ChangeStockRequest(int quantity) {}

    public record StockResponse(Long productId, int quantity) {}

    public record CreateRequest(Long brandId, String name, long price) {}

    public record UpdateRequest(String name, long price) {}
}
