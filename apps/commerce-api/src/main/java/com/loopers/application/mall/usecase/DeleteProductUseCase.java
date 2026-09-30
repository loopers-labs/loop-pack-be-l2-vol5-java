package com.loopers.application.mall.usecase;

import com.loopers.application.mall.command.ProductCommand;

// 상품 삭제 유스케이스
public interface DeleteProductUseCase {
    void execute(ProductCommand.Delete command);
}
