package com.loopers.application.product;

// 목록 캐시에 담는 표시 값. 재고·삭제 여부는 낡으면 안 되므로 담지 않고 응답 전에 DB에서 다시 읽는다(설계 19절).
public record ProductListItem(long id, String name, long price, long brandId, String brandName, long likeCount) { }
