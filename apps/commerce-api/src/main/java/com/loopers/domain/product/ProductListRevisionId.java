package com.loopers.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

// 한 브랜드의 목록 revision은 여러 칸(slot)의 행으로 나뉜다.
// slot도 bigint로 둔다 — Hibernate는 크기가 작은 열을 앞에 두므로 int면 기본 키가 (slot, brand_id)가 되어 brand_id로 찾을 때 키를 쓰지 못한다
@Embeddable
public record ProductListRevisionId(
    @Column(name = "brand_id") Long brandId,
    @Column(name = "slot") long slot
) implements Serializable {
}
