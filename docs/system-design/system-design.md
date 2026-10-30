# 시스템 설계

이 문서는 커머스 앱과 후속 이벤트 소비 구현의 기준이 되는 API 계약과 설계 원칙을 정의한다. 구현은 이 문서에 명시된 계약과 도메인 규칙을 따르며, 외부 동작에 영향을 주지 않는 세부사항은 구조와 의존성 원칙 안에서 결정한다. 문서에 없는 기능은 추가하지 않고, 구현에 필요한 외부 정책이 정해지지 않았으며 적용할 잠정 기준도 없다면 확인 후 반영한다. `[잠정]` 정책은 기획 결정에 따라 변경될 수 있지만, 결정 전까지는 문서에 적힌 현재 값을 구현과 테스트의 기준으로 사용한다.

2주차 설계 원본인 [`docs/week2/system-design.md`](../week2/system-design.md)를 기반으로 생성했으며, 이후 주차에서 발생하는 설계 변경은 이 문서 세트에 반영한다. 2주차 당시의 설계 원본은 스냅샷으로 보존하고 수정하지 않는다.

## 현재 상태

- 기존 커머스·PG 결제·PG 단독 실행 앱의 계약과 과거 검증 근거를 보존한다. 실행 범위·수량·한계는 아래 검증 기록을 따른다.
- 주문 확정 이벤트의 **발행·소비 상세설계는 2026-10-29 사용자 최종 승인 기준**이다. 승인된 단위별로 발행 P1~P9·소비 C1~C10을 구현하고 관련 검증을 실행했다. 실제 관찰·재현 방법·회귀 결과와 미검증 한계는 [주문 이벤트 실행 기록](verification/order-events.md)을 따른다. Outbox·랭킹 API·자동 재발행은 현재 범위 밖이며 DLQ는 별도 설계·승인 대상이다.
- 공통·기능별 설계·판단 근거·과거 기록으로 재편했다. 기존 절 번호와 A 식별자는 참조를 위해 유지한다. 각 계약의 원본은 아래 기준 문서 한 곳에 둔다.

## 문서 구성

| 범위 | 기준 문서 |
|---|---|
| 전체 개요·공통 원칙 | [개요](common/overview.md) · [계층·의존](common/architecture.md) · [모델 관계](common/domain-model.md) · [API 공통 규칙](common/api-contract.md) |
| 브랜드·상품·좋아요 | [카탈로그](features/catalog.md) |
| 포인트 충전·조회 | [포인트](features/points.md) |
| 상품 상세·캐시 | [상품 조회·캐시](features/product-cache.md) |
| 주문·PG 결제 | [읽기 안내](features/order-payment/README.md) · [상태·저장](features/order-payment/model.md) · [TX·처리](features/order-payment/processing.md) · [HTTP·회로](features/order-payment/pg-client.md) · [API](features/order-payment/api-contract.md) · [검증 기준](features/order-payment/verification.md) |
| 로컬 PG·테스트 도구 | [앱·HTTP 계약](features/pg-simulator/README.md) · [테스트 래퍼·프록시](features/pg-simulator/fixture-contract.md) |
| 주문 확정 이벤트 | [전체 경계·이벤트 계약](features/order-events/README.md) · [발행 상세설계](features/order-events/publication.md) · [소비 상세설계](features/order-events/consumption.md) |

## 기능별 읽기 순서

| 작업 | 읽는 순서 |
|---|---|
| 처음 참여 | 공통 개요 → 계층·의존 → 모델 관계 → 작업할 기능. API를 바꾸면 공통 API 규칙도 확인 |
| 카탈로그·포인트 | 공통 모델/API → 해당 기능 → 관련 A 절 → 필요 시 과거 실행 기록 |
| 상품 캐시 | 카탈로그 조회 계약 → 상품 조회·캐시 → 캐시 결정 → 기존 기능 실행 기록 |
| 결제 구현·검토 | 결제 읽기 안내 → 상태·저장 → TX·처리 → 관련 HTTP/API → 검증 기준 → 필요한 결정·실행 기록 |
| PG 앱·fixture | PG 앱·HTTP 계약 → 테스트 래퍼·프록시 → PG client → PG 결정·전환 검증 기준/기록 |
| 이벤트 발행 | 이벤트 전체 경계·계약 → 결제의 최초 확정·종결 보호 관련 절 → 발행 상세설계 → 이벤트 결정 |
| 이벤트 소비 | 이벤트 전체 경계·계약 → 소비 상세설계의 모델·동일 사실·TX/ack·중단/복구·C1~C10 → 관련 결정 |

각 기능의 링크를 위 문서 구성 표에서 바로 연다. 모든 작업에 전체 설계 파일 읽기를 요구하지 않으며 필요한 공통/기능 계약과 관련 근거를 함께 읽는다.

## 판단 근거와 실행 기록

| 종류 | 문서 |
|---|---|
| 판단 이유·대안·비용·재검토 조건 | [공통 도메인·API A.1~A.15](decisions/domain-and-api.md) · [상품 캐시 A.16](decisions/product-cache.md) · [PG 결제 A.17~A.32](decisions/payment.md) · [PG 앱 A.33](decisions/pg-simulator.md) · [이벤트 A.34·A.35](decisions/order-events.md) |
| 현재 검증 기준 | 기능별 검증 절. PG 전환 표처럼 기준/결과가 혼합된 기존 자료는 [PG 전환 기록](verification/pg-simulator.md)에서 함께 보존 |
| 날짜별 실제 실행·한계 | [기존 기능](verification/core-features.md) · [PG 결제](verification/payment.md) · [PG 앱 전환](verification/pg-simulator.md) · [주문 이벤트](verification/order-events.md) |
| 과거 계약·승인·진척 | [이전 POINT 설계·검증](verification/legacy-point.md) · [설계 변경 이력](verification/design-history.md) |

과거 기록의 당시 미구현/미검증·검사 수량은 해당 시점의 설명이다. 현재 기능의 계약이나 이번 작업의 실행 결과로 대체하지 않는다. 삭제된 상품·과거 POINT 주문 등 여전히 유효한 보존 계약은 현재 기능 문서에서도 유지한다.

`sections/`는 기존 경로·절 번호에서 새 원본 위치를 찾는 안내만 제공한다. 계약 본문은 중복 보관하지 않는다. 새 기준 문서는 기존 추적 설계의 이동 대상이며 Git 인덱스는 이번 작업에서 변경하지 않았다.
