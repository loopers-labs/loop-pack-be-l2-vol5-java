package com.loopers.application.mall.command;

// 브랜드 생성·수정·삭제 커맨드 모음
public final class BrandCommand {
    private BrandCommand() {}

    public record Create(String name, String description) {}

    public record Update(long brandId, String name, String description) {}

    public record Delete(long brandId) {}
}
