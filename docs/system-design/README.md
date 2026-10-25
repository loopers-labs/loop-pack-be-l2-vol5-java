# 시스템 설계 문서

Commerce API의 설계를 책임별로 안내한다. 시스템 구조와 도메인 규칙, 고객·관리자 API, 유스케이스, 외부 연동, 영속성·오류 변환 순으로 읽는다.

## 시스템 구조와 도메인

시스템의 경계와 계층별 책임, 도메인 관계와 업무 규칙을 확인한다.

| 문서 | 다루는 내용 |
| --- | --- |
| [시스템 개요](system-overview.md) | 시스템 경계, 계층별 책임, 액터와 유스케이스 |
| [도메인 모델](domain-model.md) | 도메인 관계, 애그리게이트 경계, 규칙과 불변식 |

## 고객·관리자 API

고객과 관리자가 호출하는 HTTP API의 입력·응답을 확인한다. 멱등성·재요청과 같은 처리 정책은 해당 유스케이스 문서를 따른다.

| 문서 | 다루는 내용 |
| --- | --- |
| [API 계약](api-contracts.md) | 고객·관리자 API의 HTTP 경로, 입력, 응답, 오류와 대표 기대값 |

## 유스케이스

개별 기능의 처리 정책, 객체 협력과 트랜잭션·경합·실패 처리를 확인한다.

### 상품·브랜드

| 문서 | 다루는 내용 |
| --- | --- |
| [고객 상품 상세 조회](use-cases/product-detail.md) | 상세 조회 흐름과 상품·브랜드·좋아요 결과 조합 |
| [상품 목록 조회](use-cases/product-listing.md) | 필터·정렬·페이지 조회와 좋아요 수 조합 |
| [상품 목록 캐시](use-cases/product-list-cache.md) | 캐시 키·무효화·최신성, Redis 장애와 기동 시 동작 |
| [브랜드와 연결 상품 일괄 삭제](use-cases/brand-deletion.md) | 삭제 계약, 트랜잭션, 경합, 롤백 |

### 주문·결제

결제 요청·PG 연동·결과 반영과 callback·reconcile·주기 조회를 구현했다. 조회 CircuitBreaker, Retry, RateLimiter, TimeLimiter는 [회복 정책과 실험 프로필](use-cases/order-payment-recovery.md#조회-회복-정책)로 적용하며 기본 프로필에서는 비활성화한다. 운영 활성화 등 후속 결정은 같은 문서의 미정 사항으로 남긴다. 각 문서 상단의 안내에서 결제 흐름을 연결한다.

| 문서 | 다루는 내용 |
| --- | --- |
| [주문 결제 요청](use-cases/order-payment-request.md) | 멱등성·재요청 정책, 재고 차감과 PG 요청 조율, 동시성 |
| [주문 결제 결과 반영](use-cases/order-payment-result.md) | 결과 판정, 중복 반영 방지, 충돌 기록, 주문 품목 스냅샷 기반 재고 보상 |
| [주문 결제 회복](use-cases/order-payment-recovery.md) | 수동 재확인·주기 조회, 조회 회복 정책과 실행 프로필, 재시작 후 확인과 복구 한계 |

## 외부 시스템 연동

외부 시스템과 송수신하는 계약과 adapter 규칙을 확인한다. PG의 callback 수신 계약도 PG 연동 문서에서 관리한다.

| 문서 | 다루는 내용 |
| --- | --- |
| [PG 연동 설계](external-systems/payment-gateway.md) | 결제 생성·조회와 callback 계약, adapter의 관찰 결과, 연동 설정 |
| [로컬 테스트 PG](external-systems/test-payment-gateway.md) | 별도 앱·컨테이너의 실행 경계, Commerce 테스트의 Testcontainers 실행, 시나리오 제어, 구현한 자동 성공·웹훅 흐름과 후속 작업 |

## 영속성과 오류 변환

계층 경계에서 모델을 저장·복원하거나 업무 오류를 HTTP 응답으로 변환하는 방식을 확인한다.

| 문서 | 다루는 내용 |
| --- | --- |
| [영속성 매핑 설계](persistence-mapping.md) | Domain과 JPA 저장 모델의 경계·변환, 결제 스키마와 과거 데이터 전환 |
| [오류 변환 설계](error-mapping.md) | Domain 오류 표현과 HTTP/API 응답 변환 방식 |

## 문서 갱신 기준

- 규칙의 기준 문서는 책임에 맞는 한 곳에 두고, 다른 문서에서는 요약과 링크로 연결한다. 여러 문서에 걸친 흐름은 각 문서 상단의 안내로 연결한다.
- 시스템·도메인·API·영속성·오류 문서는 폴더 루트에, 외부 연동은 `external-systems/`, 유스케이스는 `use-cases/`에 둔다. 이 README에서는 파일 위치와 함께 문서의 책임별로 안내한다.
- 구현 진행 중인 설계는 상태를 명시한다. 구현된 동작은 해당 설계 문서와 관련 테스트를 기준으로 확인한다.
- 유스케이스 정책이나 애그리게이트 불변식이 바뀌면 해당 문서와 검증 기대값을 함께 갱신한다.
- 측정한 성능 수치와 실행 원본은 설계 계약과 분리해 [상품 목록 성능 기록](../performance/product-listing/README.md), [정상 PG 결제 기록](../performance/payment-normal/README.md), [PG 조회 회복 기록](../performance/payment-lookup/README.md)에 둔다.
