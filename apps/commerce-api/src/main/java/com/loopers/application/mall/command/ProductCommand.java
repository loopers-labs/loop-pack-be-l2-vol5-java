package com.loopers.application.mall.command;

// 상품 생성·수정·삭제·재고설정 커맨드 모음
public final class ProductCommand {
    private ProductCommand() {}

    public record Create(long brandId, String name, String description, long price, int stock) {}
    public record Update(long productId, String name, String description, long price) {}
    public record Delete(long productId) {}
    public record SetStock(long productId, int stock) {}
}
