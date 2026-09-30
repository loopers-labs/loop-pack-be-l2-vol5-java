package com.loopers.application.mall.usecase;

import com.loopers.application.mall.command.BrandCommand;

// 브랜드 삭제 유스케이스
public interface DeleteBrandUseCase {
    void execute(BrandCommand.Delete command);
}
