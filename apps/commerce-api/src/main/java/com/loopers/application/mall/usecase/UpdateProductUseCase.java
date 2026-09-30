package com.loopers.application.mall.usecase;

import com.loopers.application.mall.command.ProductCommand;

// 상품 수정 유스케이스
public interface UpdateProductUseCase {
    long execute(ProductCommand.Update command);
}
