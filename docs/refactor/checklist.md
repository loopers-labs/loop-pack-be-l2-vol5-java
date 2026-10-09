# 패키지 구조 리팩토링 체크리스트

[계획](plan.md) · [결정 기록](context-notes.md)

## 커밋 0 — 문서
- [x] plan.md · checklist.md · context-notes.md 작성
- [x] `docs: 패키지 구조 리팩토링 계획과 결정 사항 정리` 커밋

## 커밋 1 — domain
- [x] main 파일을 `domain.<ctx>.{model,repository,policy}`로 `git mv`
- [x] 테스트를 대상 클래스와 같은 패키지로 `git mv`
- [x] package 선언과 모든 참조 import 갱신 (application·infrastructure·interfaces·테스트 포함)
- [x] 기존 feature 폴더(main·test) 제거 확인
- [x] 컴파일 + Checkstyle + ArchUnit 통과
- [x] 기존 패키지명 `git grep` 잔여 0건
- [x] `refactor: domain 패키지를 컨텍스트·종류 구조로 재배치` 커밋

## 커밋 2 — infrastructure
- [x] main 파일을 `infrastructure.{persistence,query,dao,scheduler,initializer}.<ctx>`로 `git mv`
- [x] `UserJpaRepository`만 `public`으로 변경 (그 외 접근제어 변경 없음)
- [x] 테스트를 매핑표대로 `git mv`
- [x] package 선언과 import 갱신
- [x] 기존 feature 폴더(main·test) 제거 확인
- [x] 컴파일 + Checkstyle + ArchUnit 통과
- [x] 기존 패키지명 `git grep` 잔여 0건
- [x] `refactor: infrastructure 패키지를 종류·컨텍스트 구조로 재배치` 커밋

## 커밋 3 — application
- [x] main 파일을 `application.<ctx>.{usecase,service,command,result,query,dao}`로 `git mv`
- [x] 조회 모델 7개 이름을 `*View`로 변경 (파일명·선언·참조)
- [x] 테스트를 `application.<ctx>.service`로 `git mv`
- [x] package 선언과 infrastructure·interfaces·테스트의 import 갱신
- [x] 기존 feature 폴더(main·test) 제거 확인
- [x] 컴파일 + Checkstyle + ArchUnit 통과
- [x] 기존 패키지명·기존 타입명 `git grep` 잔여 0건
- [x] `refactor: application 패키지를 컨텍스트·종류 구조로 재배치하고 조회 모델명을 View로 통일` 커밋

## 커밋 4 — interfaces
- [x] main 파일을 `interfaces.api.<ctx>.{controller,dto}`로 `git mv`
- [x] E2E 테스트를 `interfaces.api.<ctx>.controller`로 `git mv`
- [x] package 선언과 import 갱신
- [x] 기존 feature 폴더(main·test) 제거 확인
- [x] 컴파일 + Checkstyle 통과 (ArchUnit·테스트는 사용자 요청으로 이번 커밋에서 미실행)
- [x] 기존 패키지명 `git grep` 잔여 0건
- [x] `refactor: interfaces 패키지를 컨텍스트·종류 구조로 재배치` 커밋

## 커밋 5 — 결과 문서
- [x] `./gradlew :apps:commerce-api:check` 전체 통과 (Docker 필요) — 테스트 경량화 후 check로 확인
- [x] result.md 작성 (실제 결과·계획과의 차이·검증 수치)
- [x] CLAUDE.md "commerce-api package structure" 설명 갱신
- [x] AGENTS.md 32행 패키지 규칙 갱신
- [x] `docs: 패키지 리팩토링 결과와 구조 설명 갱신` 커밋

## 추가 작업 — OrderRecord (`volume-3/refacto-order-record`)

### 커밋 A1 — 문서
- [x] R02 트레이드오프 10 작성, 08·total_trade_off에 연결
- [x] plan.md 추가 작업 절, 체크리스트, 결정 기록 갱신
- [x] `docs: OrderBill을 ordering의 OrderRecord로 옮기는 트레이드오프 정리` 커밋

### 커밋 A2 — 이관·이름 변경
- [x] OrderBill·OrderBillStatus·OrderBillRepository와 infrastructure 3종을 ordering으로 `git mv` + 이름 변경
- [x] 테이블·제약명 `order_records`로 변경, JdbcOrderQueryDao·테스트 SQL 갱신
- [x] 참조 코드·테스트 갱신, 변수명·주석 정리
- [x] 컴파일 + Checkstyle 통과
- [x] 관련 테스트 + ArchUnit 통과
- [x] 기존 이름 `git grep` 잔여 0건
- [x] `refactor: OrderBill을 ordering 컨텍스트의 OrderRecord로 이관` 커밋

### 커밋 A3 — 확정 흐름
- [x] `Order.confirm()`이 `OrderRecord` 반환
- [x] OrderConfirmationPolicy를 검증 → 결제 단계 → 주문 단계로 정리, OrderConfirmation에 orderRecord 추가
- [x] ConfirmOrderService가 기록을 만들지 않고 저장만
- [x] 테스트 최소 수정 + `Order.confirm()` 반환값 테스트 추가
- [x] 컴파일 + Checkstyle 통과
- [x] 관련 테스트 + ArchUnit 통과
- [x] `refactor: 주문 확정을 결제 단계와 주문 기록 단계로 나누고 Order.confirm이 OrderRecord를 반환` 커밋

### 병합
- [x] `volume-3/refacto`로 fast-forward 병합

## 추가 작업 2 — OrderRecord 애그리거트 편입 (`volume-3/refacto-order-aggregate`)

### 커밋 B1 — 문서
- [x] R02 트레이드오프 11 작성, 10·total_trade_off에 연결
- [x] plan.md 추가 작업 2 절, 체크리스트, 결정 기록 갱신
- [x] `docs: OrderRecord를 Order 애그리거트에 포함하는 트레이드오프 정리` 커밋

### 커밋 B2 — 구현
- [x] 도메인: OrderRecord orderId 제거, Order에 record 필드·getRecord()·restore 불변식, confirm() void
- [x] OrderConfirmation·정책·ConfirmOrderWriter·ConfirmOrderService 정리
- [x] JPA @OneToOne 매핑(record가 FK 주인, cascade), 매퍼 갱신, 독립 저장소 삭제
- [x] 테스트 이관·강제 수정·새 테스트
- [x] 컴파일 + Checkstyle 통과
- [x] 관련 테스트 + ArchUnit 통과
- [x] `refactor: OrderRecord를 Order 애그리거트의 1:1 자식 엔티티로 편입` 커밋

### 병합
- [x] `volume-3/refacto`로 fast-forward 병합

## 테스트 경량화 (`volume-3/refacto-test-slim`)

- [x] 1단계 스트림 A(공용 spy로 컨텍스트 통합)
- [x] 1단계 스트림 B(DB 정리·컨테이너 재사용)
- [x] 1단계 스트림 C(태그 분리·Redis 제거·중복 테스트 정리)
- [x] 2단계 `@IntegrationTest`/`@E2ETest` 공용 어노테이션, `slow`/`example` 태그 부착
- [x] 레이어별 테스트 문서(`docs/test/`) 작성
- [x] `./gradlew :apps:commerce-api:check` 전체 통과
- [x] `volume-3/refacto`로 fast-forward 병합
