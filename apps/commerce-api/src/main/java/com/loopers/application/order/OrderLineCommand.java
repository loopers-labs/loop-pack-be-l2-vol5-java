package com.loopers.application.order;

public record OrderLineCommand(Long productId, Long quantity) {}
