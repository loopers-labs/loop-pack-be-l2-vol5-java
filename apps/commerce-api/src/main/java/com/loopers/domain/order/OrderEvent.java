package com.loopers.domain.order;

public sealed interface OrderEvent permits OrderConfirmed {
}
