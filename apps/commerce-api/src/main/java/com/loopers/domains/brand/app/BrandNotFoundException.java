package com.loopers.domains.brand.app;

import com.loopers.common.error.NotFoundException;

public class BrandNotFoundException extends NotFoundException {
    public BrandNotFoundException() {
        super("브랜드를 찾을 수 없습니다.");
    }
}
