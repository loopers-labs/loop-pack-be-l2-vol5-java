package com.loopers.domains.brand.app;

public class BrandNotFoundException extends RuntimeException {
    public BrandNotFoundException() {
        super("브랜드를 찾을 수 없습니다.");
    }
}
