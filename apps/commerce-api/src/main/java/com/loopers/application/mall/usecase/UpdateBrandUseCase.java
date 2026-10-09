package com.loopers.application.mall.usecase;

import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.result.BrandResult;

// 브랜드 수정 유스케이스
public interface UpdateBrandUseCase {
    BrandResult execute(BrandCommand.Update command);
}
