package com.loopers.application.product;

import java.util.List;

public record ProductPage<T>(List<T> items, int page, int size) {}
