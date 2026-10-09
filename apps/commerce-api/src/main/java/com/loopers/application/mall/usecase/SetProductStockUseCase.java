package com.loopers.application.mall.usecase;

import com.loopers.application.mall.command.ProductCommand;

// 상품 재고 설정 유스케이스
public interface SetProductStockUseCase {
    long execute(ProductCommand.SetStock command);
}
