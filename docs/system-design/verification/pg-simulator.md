# PG 앱 전환 검증 기준·실행 기록

기존 절 번호 참조: [5.5](../features/pg-simulator/README.md#55-로컬-pg-http-계약).

기존 문서의 계약·절 번호·승인/검증 상태를 보존해 이동했다. 재편 자체는 제품 계약 변경이나 새 실행 검증을 뜻하지 않는다.

## PG 전환 검증 기준과 당시 실행

### PG 단독 실행 앱 전환의 검증 — 2026-10-25 목표

**상태:** 2026-10-25 R1~R4 실행 완료. R4 수동 실행에서 세 금액 경로의 최종 주문 상태·재고와 콜백 유실 뒤 재기동·reconcile 회복(PG 생성 1회)을 1회 관찰했다. R3 전체 check에서 commerce-api 831건 fail/error 0(skip 2는 환경 변수로 켜는 미커밋 실험 테스트), pg-simulator 79건 fail/error/skip 0, Checkstyle 위반 0을 확인했다. 실험 테스트 2개를 따로 켜서 실행한 결과도 교차 확인 21/21 일치로 5주차 관찰과 같았다. 구조는 [2.10](../features/pg-simulator/README.md#210-pg-시뮬레이터-앱의-책임과-수명)·[5.7](../features/pg-simulator/fixture-contract.md#57-테스트-pg-제어와-상태-보존), 결정은 [A.33](../decisions/pg-simulator.md#a33-pg-단독-실행-앱으로의-전환)을 따른다. 위 검증 경계·시나리오의 기대값은 바꾸지 않는다.

|단계|검증|통과 기준|
|---|---|---|
|PG 모듈|5.5 계약(202/200/422/400/404·동일 orderId 복수 거래·조회 0/1/복수), `amount % 3` 세 경로, 자동 처리의 지연 뒤 상태 변경·콜백 1회·실패 시 재전송 없음, 자동 처리 끈 상태의 상태 변경·콜백 0, 콜백 결과 구분, close 뒤 worker 종료|모듈 테스트 fail/error 0. commerce-api 테스트 classpath에서 PG 클래스 사용 가능|
|테스트 래퍼·프록시|reply 4종(DELAY·GATE·CLOSE_EARLY·RAW)이 PG 거래 효과 뒤 적용됨, 400 검증 실패 시 plan 미소비, createOutcome과 reply의 소비 정렬, 프록시 수신 수 = PG 수신 수, gate 시간 초과·close 중 응답 없이 종료, reset busy 거절|래퍼 테스트 fail/error 0|
|기존 테스트 전환|PG fixture를 쓰는 commerce-api 테스트 전체|기대값·검증 내용 변경 없이 fail/error 0. import 정리 외 변경은 이유를 기록. 5주차 fixture 자체 테스트는 메서드별 이동/제거 근거를 남김|
|수동 실행|PG 앱(18080)과 commerce-api를 함께 실행. 6000원·7000원·8000원 같은 세 금액 경로의 confirm 응답과 최종 주문 상태, commerce 중지 중 자동 콜백 유실 뒤 재기동·reconcile 회복|실제 실행 기록. 자동화 테스트 통과로 대체하지 않음|
|전체 회귀|`check`(테스트·Checkstyle·ArchUnit)|fail/error 0, 의도한 skip만. ArchUnit이 pg-simulator 클래스를 규칙 대상으로 잡지 않음|

**R5 최종 회귀(2026-10-25):** R4 이후 코드 변경 없이 `:apps:commerce-api:check :apps:pg-simulator:check :apps:pg-simulator:bootJar --rerun-tasks`를 다시 실행해 성공했다. commerce-api 831건 fail/error 0, skip 2(환경 변수로 켜는 미커밋 실험 테스트, 의도된 skip), ArchUnit 3건 포함. pg-simulator 79건 fail/error/skip 0. Checkstyle commerce-api main 157·test 119, pg-simulator main 16·test 6 파일 위반 0. bootJar와 plain jar가 함께 생성됐다.
