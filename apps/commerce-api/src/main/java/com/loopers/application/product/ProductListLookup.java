package com.loopers.application.product;

/**
 * 목록 캐시 조회 결과와, 조회에 쓴 키(버전 포함)를 함께 담는다.
 * 채우기(putProductIds)는 이 키에 그대로 저장한다 — 저장 시점에 버전을 다시 읽으면, 그 사이 순서를 바꾸는 쓰기가
 * 버전을 올렸을 때 옛 DB 결과가 새 버전 키에 들어가 무효화가 무력해진다. 옛 버전 키에 들어간 값은 아무도 읽지 않고 TTL로 사라진다
 * (docs/week4/design.md 6번 섹션 "목록 키에 버전을 넣는 이유").
 * key는 캐시 구현이 만든 불투명한 값이다 — application은 형식을 해석하지 않는다.
 * 버전을 읽지 못했으면(Redis 실패) key는 null이고 result는 unavailable이다.
 */
public record ProductListLookup(String key, CacheResult<CachedProductIds> result) {
}
