package com.loopers.application.mall.usecase;

import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.result.BrandResult;

// 브랜드 생성 유스케이스
public interface CreateBrandUseCase {
    BrandResult execute(BrandCommand.Create command);
}
