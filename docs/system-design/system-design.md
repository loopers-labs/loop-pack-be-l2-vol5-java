# 시스템 설계

이 문서는 커머스 앱과 후속 이벤트 소비 구현의 기준이 되는 API 계약과 설계 원칙을 정의한다. 구현은 이 문서에 명시된 계약과 도메인 규칙을 따르며, 외부 동작에 영향을 주지 않는 세부사항은 구조와 의존성 원칙 안에서 결정한다. 문서에 없는 기능은 추가하지 않고, 구현에 필요한 외부 정책이 정해지지 않았으며 적용할 잠정 기준도 없다면 확인 후 반영한다. `[잠정]` 정책은 기획 결정에 따라 변경될 수 있지만, 결정 전까지는 문서에 적힌 현재 값을 구현과 테스트의 기준으로 사용한다.

2주차 설계 원본인 [`docs/week2/system-design.md`](../week2/system-design.md)를 기반으로 생성했으며, 이후 주차에서 발생하는 설계 변경은 이 문서 세트에 반영한다. 2주차 당시의 설계 원본은 스냅샷으로 보존하고 수정하지 않는다.

## 현재 상태

- **쿠폰 승인 설계(2026-11-11):** [쿠폰 상세설계](features/coupons.md)와 [판단 근거](decisions/coupons.md)에 모델·API·DB/TX·Redis 수용·제어 API·수동 재개·준비 코드·검증 기준을 기록했다. 일곱 차례 Claude 정적 검토와 합의한 보완을 마친 전체 설계를 사용자가 최종 승인했다. Q9·Q10은 기존 로컬 Redis를 명시 실행으로 검증하고 Q9의 연결 ID·서버 run_id 및 후속 응답을 확인해 자기 token을 정리한다. 실패 테스트의 파일 기록·사후 자기 점유 정리·새 정리 연결 순서 보완본의 최종 정적 검토에서 필수 수정·계약 공백·문서 모순은 발견되지 않았다. TX 미시작/종료 불명, 정상 첫 반환 0/반복 정리, 보유 목록의 저장된 발급 시각, 전 프로필 제어·C 기본값, 명시 준비 코드와 공유 Redis 장애/DB 대역 검증을 구분했다. Map·전역 진행 수/불명 추적·재개용 Java 잠금은 제외한다. 구현 1단계(DB 저장·조회 계약), 2단계(Redis 수용 Lua·저장소·수용 서비스·수용량 설정), 3단계(Facade·고객 발급/목록·제어 API), 4단계(직접 실행용 준비 코드), 5단계(실제 HTTP 응답 유실·timeout·시간 경과와 명시 실행 로컬 Redis 장애 Q9·Q10)의 실행 기록과 Q1~Q16 대조·남은 한계는 [쿠폰 실행 기록](verification/coupons.md)에 있다. 기본 구현의 최종 전체 검사 결과도 같은 실행 기록을 따른다. 구현 단위별 착수 승인은 별도다.
- **쿠폰 확장 정상 구현·전체 검사 완료(2026-11-12):** [쿠폰 상세설계 §12](features/coupons.md)·판단 CQ16의 승인 정상 범위를 1~5단위로 구현했다. 단일 앱/단일 운영 캠페인의 대기실·실행 자리 통합, 등록 입구 RateLimiter, 고정30초 입장 권한, 내부 실행기 scheduler, cleanup 제거를 포함한다. 기준 `3bc158a`의 전체 check에서 일반1278건 실행/통과·opt-in2건 미실행, 별도O11 1건 통과와 WQ1~WQ12 대조를 확인했다. 상세 근거/실제·대역/한계는 [쿠폰 실행 기록](verification/coupons.md)의 정상 범위 최종 전체 검사 절을 따른다. §11/CQ15는 구안이다. 캐시·장애별 복구/재시작·부하 비교는 후속이며 Bulkhead는 제외한다. 이번 정상 구현 검증으로 완료를 주장하지 않는다.
- 기존 커머스·PG 결제·PG 단독 실행 앱의 계약과 과거 검증 근거를 보존한다. 실행 범위·수량·한계는 아래 검증 기록을 따른다.
- **현재 구현·과거 승인:** 주문 이벤트 W6의 2026-10-29 승인 설계에 따라 직접 발행 P1~P9·소비 C1~C10을 구현·검증했다. 실제 관찰·회귀 결과와 한계는 [주문 이벤트 실행 기록](verification/order-events.md)을 따른다. 그 기록의 Outbox·자동 재발행 제외는 W6 당시 경계이며 W7 요구를 제외한다는 뜻이 아니다.
- **W7 승인 설계(2026-11-04):** [발행 설계](features/order-events/publication.md)의 W7~W7.4는 같은 확정 TX의 Outbox·API 내부 단일 scheduler/relay·BLOCKED/재개·명시 주문 복구를 정의한다. v1 사건·PG/HTTP·소비 정합성은 유지한다. W6 보존 구성·검증 기준·관련 문서의 통합 초안을 작성했다. 세 차례 Claude 정적 검토를 대조하고 사용자 선택/문서 보완 승인을 반영했다. 신규 전달 O6→O7/O9와 별도 W6 복구 O11의 자원/증거 범위를 정리했다. 2026-11-04 사용자가 최종 요약과 원문 부합 검토 뒤 설계 문서 스테이징 및 구현 검토 탭 인계를 요청해 전체 설계를 최종 승인했다. 구현/실행 검증은 미실행이며 구현 단위별 착수 허락은 별도다. 랭킹 API·DLQ는 추가하지 않는다.
- 공통·기능별 설계·판단 근거·과거 기록으로 재편했다. 기존 절 번호와 A 식별자는 참조를 위해 유지한다. 각 계약의 원본은 아래 기준 문서 한 곳에 둔다.

쿠폰 후속 캐시는 [상세설계 §13](features/coupons.md)·CQ17에 기발급 Redis30초·등록 품절 Redis30초→local5초, positive-only/DB fallback·이름 지연/등록 품절 우선 합의를 기록했다. 사용자는 Bulkhead를 제외했다. 남은 키/타입·NX/TTL·채움/예외·로컬 한 슬롯·KQ/구현 분할을 §13.8~13.13 상세설계로 묶었으며 전체 정적 검토/보완본 재검토 후 사용자 최종 승인을 받았다. Redis A·로컬 B 구현과 관련 검사/별도O11 검토를 마쳤다. 실제 장애/복구·비교 실험·캐시 도입 후 전체 회귀는 아직이며 근거/한계는 쿠폰 실행 기록을 따른다.

## 문서 구성

| 범위 | 기준 문서 |
|---|---|
| 전체 개요·공통 원칙 | [개요](common/overview.md) · [계층·의존](common/architecture.md) · [모델 관계](common/domain-model.md) · [API 공통 규칙](common/api-contract.md) |
| 브랜드·상품·좋아요 | [카탈로그](features/catalog.md) |
| 포인트 충전·조회 | [포인트](features/points.md) |
| 상품 상세·캐시 | [상품 조회·캐시](features/product-cache.md) |
| 쿠폰 발급·수용량·요청률 제한 | [쿠폰 상세설계·수동 재개 가이드·검증 기준](features/coupons.md) |
| 주문·PG 결제 | [읽기 안내](features/order-payment/README.md) · [상태·저장](features/order-payment/model.md) · [TX·처리](features/order-payment/processing.md) · [HTTP·회로](features/order-payment/pg-client.md) · [API](features/order-payment/api-contract.md) · [검증 기준](features/order-payment/verification.md) |
| 로컬 PG·테스트 도구 | [앱·HTTP 계약](features/pg-simulator/README.md) · [테스트 래퍼·프록시](features/pg-simulator/fixture-contract.md) |
| 주문 확정 이벤트 | [전체 경계·이벤트 계약](features/order-events/README.md) · [발행 상세설계](features/order-events/publication.md) · [소비 상세설계](features/order-events/consumption.md) |

## 기능별 읽기 순서

| 작업 | 읽는 순서 |
|---|---|
| 처음 참여 | 공통 개요 → 계층·의존 → 모델 관계 → 작업할 기능. API를 바꾸면 공통 API 규칙도 확인 |
| 카탈로그·포인트 | 공통 모델/API → 해당 기능 → 관련 A 절 → 필요 시 과거 실행 기록 |
| 상품 캐시 | 카탈로그 조회 계약 → 상품 조회·캐시 → 캐시 결정 → 기존 기능 실행 기록 |
| 쿠폰 발급·수용량 | 공통 식별·계층 → 쿠폰의 합의/구체화/미정 구분 → DB·Redis 흐름 → 수동 재개 가이드 → 검증 기준 → 쿠폰 판단 근거. 초안은 최종 구현 기준이 아님 |
| 결제 구현·검토 | 결제 읽기 안내 → 상태·저장 → TX·처리 → 관련 HTTP/API → 검증 기준 → 필요한 결정·실행 기록 |
| PG 앱·fixture | PG 앱·HTTP 계약 → 테스트 래퍼·프록시 → PG client → PG 결정·전환 검증 기준/기록 |
| 이벤트 발행/W7 구현 준비 | 이벤트 전체 경계·v1 계약 → 결제 최초 확정·종결 보호 → 발행 W7~W7.4의 검증/전환 → 이벤트 결정. 2.11·4.6은 보존한 W6 기준으로 구분 |
| 이벤트 소비 | 이벤트 전체 경계·계약 → 소비 상세설계의 모델·동일 사실·TX/ack·중단/복구·C1~C10 → 관련 결정 |

각 기능의 링크를 위 문서 구성 표에서 바로 연다. 모든 작업에 전체 설계 파일 읽기를 요구하지 않으며 필요한 공통/기능 계약과 관련 근거를 함께 읽는다.

## 판단 근거와 실행 기록

| 종류 | 문서 |
|---|---|
| 판단 이유·대안·비용·재검토 조건 | [공통 도메인·API A.1~A.15](decisions/domain-and-api.md) · [상품 캐시 A.16](decisions/product-cache.md) · [PG 결제 A.17~A.32](decisions/payment.md) · [PG 앱 A.33](decisions/pg-simulator.md) · [이벤트 W6 A.34·A.35/W7 A.36 이후](decisions/order-events.md) |
| 현재 검증 기준 | 기능별 검증 절. PG 전환 표처럼 기준/결과가 혼합된 기존 자료는 [PG 전환 기록](verification/pg-simulator.md)에서 함께 보존 |
| 날짜별 실제 실행·한계 | [기존 기능](verification/core-features.md) · [PG 결제](verification/payment.md) · [PG 앱 전환](verification/pg-simulator.md) · [주문 이벤트](verification/order-events.md) · [쿠폰](verification/coupons.md) |
| 쿠폰 선택·대안·비용 | [쿠폰 CQ1~CQ16](decisions/coupons.md). 선택·대안·비용과 검증 한계이며 실행 통과 기록이 아님 |
| 과거 계약·승인·진척 | [이전 POINT 설계·검증](verification/legacy-point.md) · [설계 변경 이력](verification/design-history.md) |

과거 기록의 당시 미구현/미검증·검사 수량은 해당 시점의 설명이다. 현재 기능의 계약이나 이번 작업의 실행 결과로 대체하지 않는다. 삭제된 상품·과거 POINT 주문 등 여전히 유효한 보존 계약은 현재 기능 문서에서도 유지한다.

`sections/`는 기존 경로·절 번호에서 새 원본 위치를 찾는 안내만 제공한다. 계약 본문은 중복 보관하지 않는다. 새 기준 문서는 기존 추적 설계의 이동 대상이며 Git 인덱스는 이번 작업에서 변경하지 않았다.
