package com.loopers.application.mall.usecase;

import com.loopers.application.mall.command.ProductCommand;

// 상품 생성 유스케이스
public interface CreateProductUseCase {
    long execute(ProductCommand.Create command);
}
