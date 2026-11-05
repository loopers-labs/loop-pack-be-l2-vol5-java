package com.loopers.domains.product.app;

import com.loopers.common.error.NotFoundException;

public class ProductNotFoundException extends NotFoundException {
    public ProductNotFoundException() { super("상품을 찾을 수 없습니다."); }
}
