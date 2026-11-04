# 5주차 전체 요구사항 · 재고 선차감과 외부 결제

## 목표·기준·현재 상태

기존 주문의 포인트 결제를 로컬 테스트 PG로 교체한다. 재고를 먼저 차감해 저장하고 PG를 호출한다.
성공하면 차감한 재고를 유지하며 주문을 확정하고, 확정 실패하면 같은 수량을 한 번만 복구한다.
처리 중이거나 결과를 확인하지 못한 경우에는 차감 상태를 유지하고 기존 외부 거래를 조회한다.

| 항목 | 기준 |
|---|---|
| 준비 기준일 | 2026-10-21 (Asia/Seoul) |
| 출발 커밋 | `9eff4025228a6d81cd9e3bb4c3e675d247004e34` — `volume-4/main`의 4주차 최종 정리 |
| 기준 브랜치 | `volume-5/main` |
| 준비 산출물 | 로컬 기준 브랜치, 원문 보존, 전체·PR별 요구사항, 설계 질문, 구현 계획의 틀 |
| 구현 상태 | R01 구현·검증·PR #24 병합 완료([결과](r01-pg-payment-foundation/result.md)). R02 로컬 구현·검증 완료([결과](r02-pg-order-payment/result.md)): 전체 검사 432건·실제 PG 주문/장애/재시작 실험 10개 통과. R02 PR #25 병합 완료(`42c22c2`). R03 로컬 구현·세 관점 기능 리뷰·전체 검사 509건·실제 PG 장애/재시작 실험 14건 완료([결과](r03-payment-resilience/result.md)). 운영/상태/기술 글과 PR 설명을 정리했고 PR #26도 병합됐다(`b70d83b`). 최종 대조에서 발견한 반환 캐시 검증 공백은2건 추가·관련24건/테스트 Checkstyle 통과로 보완했다([최종 점검](final-audit.md)) |
| 작업 브랜치 | R01은 준비 커밋 `d443cdc`에서 생성해 구현·병합 완료. R02는 R01 병합 기준 4b81541에서 생성. R03은 최신 기준 42c22c2에서 `volume-5/r03-payment-resilience` 생성 완료 |

기준 자료:

- [처음 읽는 개발자 안내: 상태 모델·서킷브레이커·폴백](resilience/README.md)
- [상태 문서: 전체 흐름·주문·재고·결제](states/total.md)
- [학습 자료 원문](reference/learning.md)
- [Implementation Quest·체크리스트·Technical Writing Quest 원문](reference/implementation-quest.md)
- [저장소 작업 규칙](../../AGENTS.md), [구조·명령 안내](../../CLAUDE.md)
- [4주차 최종 범위와 결과 안내](../week4/total_requirement.md)

두 원문은 사용자 첨부를 내용 변경 없이 보존한다. 사용자 확정 사항, 원문의 요구, 아직 결정하지 않은 설계를 구분한다.
준비 문서의 존재는 구현·테스트·제출 완료를 뜻하지 않는다.

### 사용자 추가 조건 · 직접 실행하는 테스트 PG

2026-10-21 사용자는 학습용 PG 서버를 직접 만들고 commerce가 실제 HTTP로 연결하는 구성을 확정했다.
테스트 내부 HTTP fixture만으로 완료하지 않고 로컬에서 commerce와 별도로 실행할 수 있는 작은 PG를 R01에 포함한다.
거래 저장은 사용자가 처음의 메모리 선택을 철회하고 **DB(MySQL) 저장**으로 변경했다.
DB 배치는 **기존 MySQL 서버 안에 PG 전용 스키마를 분리**하는 구성으로 확정했다.
PG를 재시작해도 거래·최종 결과를 다시 조회할 수 있어야 한다. 실제 카드·실금전·외부 PG 계정은 사용하지 않는다.

원문의 생성·거래 조회·주문별 조회 경로와 기본 필드를 유지하고, 생성 요청에 학습용 테스트 조건을 추가한다.
시나리오는 PG 결제 요청에 프리셋과 필요한 세부 조건을 함께 넣는 방식으로 변경했다. 별도 제어 API는 도입하지 않는다.
실제 거래 결과와 생성 응답·조회 응답·callback 전달 조건을 구분하며, 지정하지 않은 세부 조건은 프리셋 값을 유지한다.
PG는 거래와 적용할 조건을 DB에 저장해 해당 거래의 처리와 응답을 재현한다.
PG는 설정한 처리 시간에 따라 거래를 자동 완료한다. 거래 처리 시간과 생성/조회 HTTP 응답 지연은 독립적으로 제어한다.
원문은 그대로 보존하고, 독립 실행·DB 저장·생성 요청의 테스트 옵션은 사용자 추가 조건으로 구분한다.
후속 [학습용 최소 보완](r01-pg-payment-foundation/trade_off/10-learning-scope-completion.md)으로 최초 실효 조건의 고정 필드 저장·비교, PG의 DB 예정 시각/결과 주기 조회, 재시작 실험용 테이블 보존 프로필·명시 초기화를 확정했다.
실행 모듈·포트·스키마 이름/권한·프로필/초기화 파일·옵션 세부 필드/출처·프리셋 목록·조회 주기/처리 범위·callback 세부는 R01 상세 설계에서 정한다.

## 확정한 업무 경계

### 재고를 먼저 차감한 뒤 결제

```text
로컬 TX A: 주문·상품·재고 검사 → 가용 재고 차감 → 시도·차감 기록·발송 상태 저장 → commit
TX 밖:     PG 결제 생성 또는 기존 거래 조회
관찰 TX:   검증한 PG 결과/효과 0건 확정 거절의 필요한 사실을 먼저 저장 → commit
로컬 TX B: 저장한 검증 결과 확인 → 주문·시도·재고 처리 상태를 한 번만 반영 → commit
```

문서의 '재고 예약'은 특정 미완료 주문을 위해 재고를 확보한 의미다. 이번 구현 방향은 기존 가용 재고를 먼저 줄이고,
주문별 차감 수량과 처리 상태를 DB에 보존하는 것이다. R01은 mall의 StockAdjustment/품목 기록을 저장한다. R02에서 실제 상품 수량 변경과 업무 TX 연결을 완료했다.
PG HTTP 호출 동안 DB 트랜잭션이나 재고 잠금을 유지하지 않는다.
application의 조율 파사드와 별도 준비/공통 결과 반영 TX 컴포넌트를 두는 [A안](r02-pg-order-payment/trade_off/01-facade-transaction-boundary.md)을 채택했다. 전체 흐름을 하나의 TX로 감싸지 않고 PG HTTP는 TX 밖에서 수행한다. callback·수동 대사·자동 재확인은 같은 결과 반영 TX 진입점을 사용한다. R02에서 확정한 클래스/Context·저장/잠금 순서는 [구현 계획](r02-pg-order-payment/plan.md)을 따른다.
후속 결정으로 검증 관찰 저장 진입점도 별도 짧은 TX로 분리해 TX B 전에 commit한다. TX B 실패로 관찰이 사라지지 않으며 저장된 신뢰할 종결 결과는 재조회 없이 재적용할 수 있다. 특히 거래 없는 확정 거절은 저장 근거로 재적용하고 빈 목록/404로 실패를 새로 판단하지 않는다. 관찰 저장 전 종료의 미확인 한계는 유지한다.

| PG 관찰 | 주문·결제 처리 | 재고 처리 |
|---|---|---|
| 검증한 `PENDING` | 결제 대기 | 차감 상태 유지 |
| timeout·응답 유실·조회 불가 | 이번 관찰 확인 불가. 마지막 검증 결과 보존, 확인한 결과가 없으면 미확인 | 차감 상태 유지 |
| 검증한 `SUCCESS` | 주문 확정·PG 결제 사실 저장 | 이미 차감한 재고 유지, 추가 차감 없음 |
| 검증한 `FAILED` | 주문 실패 | 주문별 차감 수량을 한 번만 복구 |
| 계약에 맞는 생성 전 효과 0건 확정 거절 | 주문 실패, 없는 거래 키를 만들지 않음 | 한 번만 복구 |
| 조회 0건·복수 거래·금액/식별 불일치 | 미확인·충돌 보존, 재발송 금지 | 임의 복구 없음 |

재고 5개에서 수량 2개를 차감하면 3개다. 성공 후에도 3개, 확정 실패 후에는 5개여야 한다.
중복 실패 적용으로 7개가 되거나 결과 미확인에서 5개로 돌아가면 안 된다.

### 결제 시도·외부 결과

- 결제 시도는 pay의 독립 모델·저장소로 두고 orderId로 주문과 연결한다. OrderRecord는 주문 확정 기록으로 유지하며 application이 같은 commerce DB의 로컬 TX에서 주문·시도·재고를 조율한다.
- [확인한 결제 결과와 복구 진행을 나눈다](r01-pg-payment-foundation/trade_off/05-payment-attempt-state.md). 수동 전환·조회 오류가 검증한 PG 사실을 덮어쓰지 않으며, PG 결과 확인과 내부 업무 반영 완료를 구분한다. R01의 구체 상태·허용 조합은 [구현 계획](r01-pg-payment-foundation/plan.md)과 [결제 상태](states/payment.md)를 따른다.
- [복구 진행은 별도 pay 작업 모델·저장소로 분리](r01-pg-payment-foundation/trade_off/06-recovery-progress-storage.md)한다. 사용 횟수·현재 실행/진행·최근 시각·오류를 우선 보존하고 같은 commerce DB의 로컬 TX에서 조율한다. commerce의 지터/다음 예약 시각·재시작 대기 재개 정보는 영속화하지 않는다. PG의 완료 예정 시각은 별도 사실로 저장한다.
- [재고 차감·복구 기록은 mall의 별도 모델](r01-pg-payment-foundation/trade_off/07-stock-adjustment-boundary.md)로 둔다. 확정 실패 반영 TX에서 실제 재고 증가와 복구 완료 기록·관련 주문/결제/작업 변경을 함께 보호한다. 별도 비동기 재고 복구 worker는 두지 않으며 저장 구조는 R01에서 구현했고 실제 업무 반영의 잠금 순서도 R02에서 확정·구현했다.
- R02 전환 후 신규 결제와 기존 미확정 DRAFT의 결제는 PG를 사용한다. 포인트 차감은 0회이며 과거 POINT 결제 사실은 보존한다.
- 주문당 결제 시도는 하나다. 내부 주문 ID·시도 키·PG용 주문 ID·PG 거래 키를 구분해 DB에 보존한다.
- [결제 시도 키는 전체 결제 시도에서 전역 유일](r01-pg-payment-foundation/trade_off/02-idempotency-request-contract.md)하다. 고객이 달라도 같은 키를 다른 주문에 재사용하면 충돌한다. 주문당 한 시도 제약은 별도로 유지하며 R01은 표준 UUID 소문자 정규화·DB 전역/주문 유일 제약을 사용한다.
- 같은 요청의 일치를 검사하고, 같은 주문에 새 키를 보내도 두 번째 결제를 만들지 않는다. 메모리 잠금만 사용하지 않는다.
- 같은 주문·키로 재요청할 때 최초 적용한 PG 테스트 조건을 고정 필드로 저장·비교한다. 기본값을 채운 실효값이 기준이며 생략/명시·JSON 순서는 같게 처리한다. 조건이 다르면 충돌이며 최초 조건을 유지한다. 학습 중 프리셋 기본값은 고정하고 런타임 편집/버전 관리 플랫폼은 도입하지 않는다. 실효 조건 9개와 고정 프리셋 기본값은 R01 구현 계획을 따른다.
- 학습 범위 단순화를 위해 카드 종류/번호는 요청 비교에서 제외한다. 같은 주문·키·저장 금액·실제 테스트 조건이면 카드 입력이 달라도 기존 시도 상태를 반환한다. 카드로부터 만든 HMAC/해시 비교값과 관련 비밀키 관리는 도입하지 않는다. 카드 원문 비저장/로그 금지·형식 검증은 유지한다. 원문의 '다른 요청 충돌'에서 카드 입력을 제외하는 사용자 승인 변경이며 원문 자체는 보존한다.
- 발송 시작을 영속 기록한 뒤 create를 자동 재시도하지 않는다. 복구는 저장된 검증 종결 결과의 재적용 또는 기존 거래 권위 조회 경로이며 새 결제가 아니다.
- callback 본문은 알림이다. PG 권위 조회에서 주문·거래·금액·제공자 계정 범위를 확인한 뒤 같은 결과 적용 경계를 사용한다.
- 중복·동시 적용은 업무 효과를 추가하지 않고, 종결 결과의 충돌이나 늦은 응답이 기존 종결 상태를 덮어쓰지 못하게 한다.
- 조회 retry·circuit breaker·fallback은 대기와 호출을 제어한다. 결제 결과를 임의로 성공·실패로 만들지 않는다.
- 장기 미확인 거래의 자동 완료는 보장하지 않는다. 시간 경과·빈 조회만으로 재결제하거나 재고를 복구하지 않는다.

실제 카드·실금전·외부 PG 계정은 사용하지 않는다. 합성 카드 번호도 DB·로그에 남기지 않고,
PG base URL·callback URL은 고객 입력이 아닌 로컬 설정으로 고정한다.
환불·정산·사용자 취소·실패 후 새 결제 시도는 이번 범위 밖이다.
원문은 수동 reconcile을 기본으로 두지만, 사용자 추가 결정으로 기존 거래를 자동 재확인하고 한도 도달 뒤 수동 확인으로 전환한다.
최초 조회 후 추가 재조회 최대 3회, 각 확인 종료 후 1초·3초·10초 대기와 지터 ±20%를 적용한다.
실패/수동 전환 분류도 [재확인 정책](r03-payment-resilience/trade_off/01-reconciliation-retry-policy.md)에서 확정했다.
R03에서 연결한 정상 실행은 [Resilience4j Retry + DB 진행 기록](r03-payment-resilience/trade_off/06-reconciliation-execution.md)으로 처리한다. 2026-10-22 후속 결정으로 자동 조회 Retry와 공유 조회 CircuitBreaker를 Resilience4j로 통일해 기존 Spring Retry @Retryable 선택을 대체했다. 기존 재조회 정책·30초 예산·DB 기록은 유지하며 [연결 문서](r03-payment-resilience/trade_off/03-resilience-library-wiring.md)를 따른다. 후속 결정으로 장애/재시작 뒤 자동 재개는 [운영 담당자 미완료 목록 조회·수동 대사](r02-pg-order-payment/trade_off/06-interrupted-payment-reconcile.md)로 변경했다. 자동 만료/갱신·회수는 보류한다.
조회 작업 직전에 [사용 횟수를 DB에 기록·commit](r03-payment-resilience/trade_off/07-retry-attempt-accounting.md)한다. 기록 뒤 실제 조회 전에 종료돼도 소비한 회차를 되돌리지 않는다.
복구 작업 실행 권한은 [멱등성 키 + 조건부 상태 갱신 + 실행 식별자](r01-pg-payment-foundation/trade_off/08-recovery-claim-coordination.md)로 보호한다. 명시적 비관적 잠금 조회를 필수로 두지 않고 조건부 갱신 성공/commit 후 PG 조회한다. DB UPDATE 내부 잠금까지 없다는 뜻은 아니며 상품 재고 동시성·공통 결과 반영 TX의 보호는 유지한다. 수동 처리 조건·필드/갱신·영속성 정합성은 R02/R03 계획과 구현을 따른다. 자동 lease는 도입하지 않는다.
수동 대사는 미완료 작업의 현재 실행 ID·상태를 조건으로 새 실행 ID를 기록해 옛 진행 갱신을 차단한다. 중단 RUNNING도 인계할 수 있어야 하며 일반 save가 최신 실행/횟수를 되돌리지 않도록 한다. 정확한 조건식·허용 상태·응답·JPA 정합성 처리는 R02 [구현 계획](r02-pg-order-payment/plan.md)과 [결과](r02-pg-order-payment/result.md)를 따른다.
[HTTP 설정](r01-pg-payment-foundation/trade_off/03-http-client-settings.md)은 RestClient + Apache HttpClient 5·자동 retry/redirect 차단으로 확정했다. 연결 1초·연결 풀 대기 1초·응답 대기 2초를 초기값으로 사용하며 전체 호출의 절대 제한으로 해석하지 않는다.
[adapter 결과·검증 분담](r01-pg-payment-foundation/trade_off/04-adapter-fixture.md)은 검증한 관찰/확인 불가 사유를 결과값으로 반환하는 A다. 실제 PG에서 정상·지연·유실·DB 보존을, 작은 테스트용 HTTP 서버에서 불완전 응답 등을 검증한다. 복구 실행기와 회로는 반환 결과를 분류해 연결한다.
총 예산·회차 기록/실행 ID 조건부 갱신·Resilience4j 버전과 사유 코드는 R03 [계획](r03-payment-resilience/plan.md)과 [결과](r03-payment-resilience/result.md) 기준으로 구현·검증했다. 결제 생성 재시도는 허용하지 않는다. HTTP 응답 검증과 실제 timeout은 R01 이후 검사 및 독립 PG 실험으로 확인했다.

2026-10-22 R03 후속 결정: 자동/수동/callback PG 조회는 하나의 회로를 공유한다. 차단 시 자동은 확보한 회차를 유지하고 즉시 수동 전환하며 실제 HTTP/회로 표본은 0이다. 첫 자동 조회는 작업 시작 후 즉시, 명확한 실행기 접수 거절은 100ms 뒤 전달만 1회 재시도한다. timeout·통신·5xx·408·429·잘못된 정상 응답은 회로 실패, 검증한 결과/계약상 거래 없음은 정상, 그 외 4xx·식별 충돌·우리 DB/실행기 오류·차단은 제외다. [집계/재조회/업무 처리 표](r03-payment-resilience/trade_off/02-lookup-circuit-breaker.md)를 따른다. 회로 수치는 최근 10건/최소 5건·실패율 50%·1초 초과 느림 비율 50%·OPEN 10초 후 요청 기반 탐색·HALF_OPEN 표본 2건/최대 10초 미판정 시 재차단이다. 자동 조회 예산은 최초 전달부터30초, 내부 반영은 전체 rollback 확인된 MySQL 데드락만100ms 뒤 추가1회로 확정해 구현·검증했다.

## R01 착수 전 구현과 5주차 추가 범위

아래 표는 출발 커밋을 분석한 당시 기록이다. R01에서 완료한 항목·실제 검증은 [결과 문서](r01-pg-payment-foundation/result.md)를 따른다.

| 영역 | 현재 구현 | 5주차 추가 범위 |
|---|---|---|
| 주문 확정 | `ConfirmOrderFacade`의 단일 TX에서 주문·지갑 잠금, 재고·포인트 차감, 주문 확정 | 재고 선차감 TX → PG HTTP → 관찰 저장 TX → 업무 적용 TX로 분리, PG 주문의 포인트 차감 제거 |
| 주문·결제 상태 | 주문 `DRAFT/CONFIRMED`, 주문 기록 `PAID` | 결제 대기·실패, 시도·발송·미확인·종결 상태의 의미와 전이 |
| 중복 방지 | 주문 잠금·재확정 거절, 주문 기록의 주문 ID 유일 제약 | 요청 키 일치, 주문당 한 시도, 한 실행의 발송 권한, 저장 결과 재반환 |
| 재고 | 비관적 잠금, 상품 ID 순서 잠금, 전체 검사 후 차감·관리자 최종 수량 설정 | 주문별 차감·처리 기록, 성공 시 유지·확정 실패 시 복구 1회 |
| 외부 경계 | PG adapter·테스트 PG·회복 도구 미구현 | DB 저장형 독립 실행 테스트 PG, 로컬 HTTP 계약, 연결·응답 timeout, 조회 회복 전략 |
| 조회 | 주문 품목 스냅샷·결제 금액·PAID 결과, 고객·관리자 경로 | 결제수단·시도·외부 거래·미완료 상태, confirm/reconcile의 202 응답과 조회 경로 |
| callback·대사 | 미구현 | 권위 조회·식별/금액 검증·공통 결과 적용·재시작 후 조회 |
| 캐시 | 상품 상세 Caffeine, 재고 변경 이벤트·AFTER_COMMIT 무효화 | 차감과 복구 각각의 commit/rollback 연결 |
| 검증 | 포인트 결제의 정상·부족·재확정·동시성·SQL 반영 후 rollback 테스트 | PG 계약·실패 순서·업무 효과 수·재시작·회로 동작 검증 |

현재 경로는 [주문 확정](../../apps/commerce-api/src/main/java/com/loopers/application/ordering/facade/ConfirmOrderFacade.java),
[재고 처리](../../apps/commerce-api/src/main/java/com/loopers/application/mall/service/ProductService.java),
[주문 조회](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/query/ordering/QueryDslOrderQueryDao.java)를 기준으로 한다.

### 원문 소유권 요구와 기존 정책의 차이

원문은 본인 주문의 확정·상세·대사와 관리자/고객 접근 구분을 요구한다.
기존 저장소는 인증·인가를 제외한 fixture 정책이며 `X-USER-ID`는 사용자 입력이지 인증이 아니다.
착수 전 주문 목록에는 사용자 조건이 있었지만 상세·확정에는 본인 검사가 없었다. 현재 confirm·상세 GET에는 소유권 검사를 연결했고 주문 관리자 GET은 1번 fixture만 허용한다.
관리자 URL 분리도 관리자 권한 검증을 뜻하지 않는다.

2026-10-22 사용자 결정으로 고객 confirm/상세/reconcile에 기존 X-USER-ID와 저장된 userId를 비교하는 소유권 검사를 추가한다.
과거 POINT 고객 상세도 같은 규칙이며 타인 주문은 없는 주문과 같은 404·오류 코드·메시지로 응답한다.
confirm·고객 상세 GET의 소유권/타인 404와 주문 관리자 GET의 fixture 검사는 구현했다. 고객/관리자 수동 대사의 접근·단일 조회·인계도 구현했다. [고객 API 결정](r02-pg-order-payment/trade_off/04-customer-ownership-and-api.md)을 따른다.
같은 날 주문 관리자 조회·미완료 목록·운영자 대사는 기존 X-USER-ID=1 관리자 fixture를 확인하기로 정했다. 고객 경로는 1번도 본인 주문만 처리한다. 상품 등 다른 관리자 API 변경·실제 로그인·역할 테이블은 포함하지 않는다.

## PR 구성·진행 규칙

| 순서 | PR·작업 브랜치 | 책임 | 상태 |
|---|---|---|---|
| 1 | R01 · `volume-5/r01-pg-payment-foundation` | DB 저장형 독립 실행 테스트 PG·adapter·HTTP timeout·create 재시도 차단, 결제/차감 기록 모델·DB 제약·과거 POINT 호환 | 로컬 구현·검증 완료·PR #24 병합 완료 |
| 2 | R02 · `volume-5/r02-pg-order-payment` | confirm 전환·멱등성·발송 제어·TX 분리·실패 복구·callback·reconcile·조회/소유권 경계 | 구현·통합/재시작·필수 전체 검사 완료, PR #25 병합 완료 |
| 3 | R03 · `volume-5/r03-payment-resilience` | 조회 회로·fallback·제한된 재조회·통합 장애 실험·회귀·설계/기술 글 정리 | 구현·리뷰·전체509건·실제PG실험14건·PR #26 병합 완료. 반환 캐시2건도 후속 관련24건/테스트 Checkstyle로 검증 완료 |

- R01: [요구사항](r01-pg-payment-foundation/requirement.md) · [설계 질문](r01-pg-payment-foundation/trade_off/total_trade_off.md) · [구현 계획](r01-pg-payment-foundation/plan.md) · [실제 결과](r01-pg-payment-foundation/result.md)
- R02: [요구사항](r02-pg-order-payment/requirement.md) · [설계 질문](r02-pg-order-payment/trade_off/total_trade_off.md) · [구현 계획](r02-pg-order-payment/plan.md) · [실제 결과](r02-pg-order-payment/result.md)
- R03: [요구사항](r03-payment-resilience/requirement.md) · [설계 질문](r03-payment-resilience/trade_off/total_trade_off.md) · [상세 계획](r03-payment-resilience/plan.md) · [실제 결과](r03-payment-resilience/result.md) · [운영 확인](r03-payment-resilience/operations.md) · [기술 글](r03-payment-resilience/technical-article.md)

각 PR의 대상은 `volume-5/main`이다. 앞 PR을 검증·병합한 뒤 최신 기준 브랜치에서 다음 작업 브랜치를 생성한다.
작업 브랜치끼리 직접 병합하지 않으며 세 작업 브랜치를 준비 시점에 미리 만들지 않는다.
요구사항 → 설계 문답·결정 → 커밋·TDD 계획 → 구현·검증 → 실제 결과 순서로 진행한다.
각 PR에 관련 테스트를 포함하고 R03에 검증을 모두 미루지 않는다. `result.md`는 실제 구현·검증 후 작성한다.

R01/R02/R03 모두 구현·검증·게시·병합했다. 현재 기준은 `volume-5/main`의 `b70d83b`다. PR03 당시 전체509건과 실제 PG 실험 근거는 PR별 결과를 따른다. 5주차 전체 대조에서 발견한 반환 commit/rollback 캐시 검증 공백은2건 추가로 보완했고 관련24건/테스트 Checkstyle이 통과했다. 기능 수정 없이 점검한 범위의 미해결 필수 보완을 닫았다. [최종 점검표](final-audit.md)에 구현·검증·제외 범위를 구분했다.
기존 패키지·계층 의존·순수 도메인·QueryDSL 조회/JPA 쓰기·TDD·Checkstyle·ArchUnit과 검사 완화 금지는 유지한다.
PG 경계의 단일 TX 분리는 과거 포인트 주문 확정의 단일 TX 규칙을 그대로 적용하는 대상이 아니다.

## PR별 검증과 최종 제출 기준

- R01: DB 저장형 테스트 PG의 독립 실행·재시작 후 거래 보존, 실제 adapter의 메서드·경로·헤더·JSON 타입·문자열 ID·금액·조회 인코딩·응답 매핑, 생성 전 확정 거절과 응답 유실의 구분.
- R02: 정상·PENDING·UNKNOWN·확정 실패, 같은 시도의 동시 confirm, 복구 중복 방지, callback/reconcile의 중복·순서 변경·동시 적용.
- R02/R03: PG 성공 후 내부 저장 실패, 발송 기록 후 재시작, 거래 0건/복수/불일치, 종결 결과 충돌과 늦은 응답을 통한 상태 회귀 방지.
- R03: 실제 timeout·조회 횟수·전체 예산·CLOSED/OPEN/HALF_OPEN·회복, 차단/조회 실패의 상태 유지와 fallback 검증.
- 공통: 외부 거래 수·create/lookup 횟수·주문/시도/차감 처리 상태·가용 재고·포인트 변화량을 함께 확인한다.
- 공통: 실제 MySQL fixture와 실제 callback controller를 사용하고, 기다림을 유한하게 제한하며 자신이 시작한 서버·worker를 정리한다.
- 회귀: 과거 POINT 기록·품목·금액, 포인트 충전/조회, 삭제 상품의 신규 결제 거절과 차감 완료 주문의 종결, 관리자 가용 재고 설정, 캐시 commit/rollback.
- 제출: 세 PR의 코드·테스트·직접 실행할 테스트 PG·보조 fixture·갱신 설계, 대표 효과 수와 미확인 한계, 실제 상황·개념·판단 이유를 담은 기술 글.

관련 테스트 이름은 구현 시 정한다. 최종 전체 검사는 `./gradlew :apps:test-pg:check :apps:commerce-api:check`
(Windows: `.\gradlew.bat :apps:test-pg:check :apps:commerce-api:check`)를 사용하며 slow/example 테스트·Checkstyle·ArchUnit을 포함한다.
테스트 0건·skip·연결 실패나 과거 결과를 이번 구현의 통과로 기록하지 않는다. 준비 문서 작업에서는 기능 검사를 실행하지 않는다.
