# 시스템 설계 문서

이 폴더는 현재 구현된 Commerce API의 기준 설계를 모은다. 문서는 주차가 아니라 시스템 주제와 별도 설명이 필요한 유스케이스를 기준으로 구성한다.

## 기준 문서

| 문서 | 다루는 내용 |
| --- | --- |
| [시스템 개요](system-overview.md) | 시스템 경계, 계층별 책임, 액터와 유스케이스 |
| [도메인 모델](domain-model.md) | 도메인 관계, 애그리게이트 경계, 규칙과 불변식 |
| [영속성 매핑 설계](persistence-mapping.md) | Domain과 JPA 저장 모델의 경계와 변환 방식 |
| [API 계약](api-contracts.md) | HTTP 경로, 입력, 응답, 오류와 대표 기대값 |
| [오류 변환 설계](error-mapping.md) | Domain 오류 표현과 HTTP/API 응답 변환 방식 |
| [브랜드와 연결 상품 일괄 삭제](use-cases/brand-deletion.md) | 삭제 계약, 트랜잭션, 경합, 롤백 |
| [주문 확정과 포인트 결제](use-cases/order-confirmation.md) | 호출 시퀀스, 상태 전이, 재고·포인트 차감, 동시성 |
| [고객 상품 상세 조회](use-cases/product-detail.md) | 상품 수정 후 조회 흐름과 상품·브랜드·좋아요 결과 조합 |
| [상품 목록 조회](use-cases/product-listing.md) | 정렬·조회·캐시·장애 계약 |

성능 근거와 재현 자료는 [상품 목록 성능 기록](../performance/product-listing/README.md), 미구현 쿠폰 요구는 [주문 할인 제안](../proposals/order-discount-contract.md)을 참고한다.

## 문서 갱신 기준

- 구현된 동작의 기준은 이 폴더의 설계 문서와 관련 테스트다.
- 유스케이스 계약이나 애그리게이트 불변식이 바뀌면 해당 기준 문서와 검증 기대값을 함께 갱신한다.
- 측정한 성능 수치와 실행 원본은 설계 계약과 분리해 성능 기록에 둔다.
