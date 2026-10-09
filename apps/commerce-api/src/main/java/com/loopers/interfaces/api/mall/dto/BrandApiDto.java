package com.loopers.interfaces.api.mall.dto;

import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.result.BrandResult;

// 브랜드 API 요청·응답 DTO 모음
public final class BrandApiDto {
    private BrandApiDto() {}

    public record Request(String name, String description) {
        public BrandCommand.Create toCreateCommand() { return new BrandCommand.Create(name, description); }
        public BrandCommand.Update toUpdateCommand(long brandId) { return new BrandCommand.Update(brandId, name, description); }
    }

    public record Response(long brandId, String name, String description) {
        public static Response from(BrandResult result) {
            return new Response(result.brandId(), result.name(), result.description());
        }
    }
}
