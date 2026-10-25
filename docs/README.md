# 문서 안내

## 현재 구현 설계

[시스템 설계 문서](system-design/README.md)에서 시스템 개요, 도메인·애그리게이트, API 계약과 주요 유스케이스를 확인한다.

## 성능 실험

[상품 목록 조회 성능 실험](performance/product-listing/README.md)은 보고서, K6 시나리오와 측정 SQL을 연결한다.

[PG 조회 지연과 Circuit Breaker 실험](performance/payment-lookup/README.md)은 서킷 미적용 기준선, 차단·재차단·회복과 주문·재고 검증 자료를 연결한다.

## 제안

[주문 할인 계약](proposals/order-discount-contract.md)은 미구현 쿠폰 적용 제안이다.
