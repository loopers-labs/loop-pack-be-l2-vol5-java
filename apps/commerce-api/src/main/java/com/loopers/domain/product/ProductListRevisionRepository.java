package com.loopers.domain.product;

public interface ProductListRevisionRepository {

    long current();

    void advance();
}
