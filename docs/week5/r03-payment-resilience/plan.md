# R03 상세 구현 계획

상태: **2026-10-23 로컬 구현·세 관점 기능 리뷰·실제 PG 장애/재시작 실험 14건·필수 전체 검사 509건 완료**. 최종 검증의 기능 기준은 `ae46776`이다. [결과](result.md)·[리뷰 기록](review.md)을 따른다. 원격 게시/PR 생성은 후속 요청 범위다. 아래 계획과 기능별 진행 기록은 당시 순서를 보존한다.

[요구사항](requirement.md) · [결정 목록](trade_off/total_trade_off.md) · [라이브러리·운영 범위](trade_off/03-resilience-library-wiring.md)

## 출발점과 지킬 정책

- R02 PR #25 병합 기준은 `42c22c2be1d6304838c3c80120d30a5f6ab2fc88`이다. 작업 브랜치는 `volume-5/r03-payment-resilience`, PR 대상은 `volume-5/main`이다.
- R02의 전체 검사 432건과 별도 서버 실험 10개는 기존 결과다. 이번 PR의 검증 완료로 세지 않는다.
- 재고 선차감, 관찰 먼저 commit/공통 업무 TX 분리, 주문 CAS, 복구 작업 CAS/실행 ID, 기존 소유권·관리자 fixture·POINT 기록·캐시는 유지한다.
- 최초 confirm의 PG 발송 권한을 얻은 실행만, PG 처리 후 미확인/처리 중이고 시작 가능한 작업에 자동 조회를 연결한다. confirm 재요청·수동 대사·callback은 새 자동 작업을 시작하지 않는다.
- 최초 조회＋추가 3회, 앞선 회차 종료 후 1/3/10초와 지터 ±20%, 최초 실행기 전달부터 30초 예산이다. 이미 시작한 조회의 관찰 저장/업무 반영은 마무리한다.
- PG 생성은 Retry/조회 회로 범위 밖이다. 수동은 저장 종결 재적용 0회/그 외 요청당 조회 최대 1회이며 callback에도 자동 조회 반복을 붙이지 않는다.
- 재시도·회로는 Resilience4j 애노테이션을 사용한다. 기존 Spring Retry @Retryable 선택은 대체했다.
- 중단/재시작 뒤 자동 회수·예산 초기화·lease·commerce 주기 스캔은 추가하지 않는다. 미완료 목록 GET과 명시적 수동 대사를 사용한다.

## 적용할 구조

### 공유 PG 조회와 회로

- application에 조회 전용 계약 `PaymentLookupGateway`를 두고, infrastructure의 별도 Spring 빈이 구현한다. 기존 `PaymentProviderGateway`는 실제 생성/조회 HTTP adapter로 재사용한다. 생성 호출은 기존 경로를 유지한다.
- 조회 전용 빈의 공개 메서드에 `@CircuitBreaker(name = "pg-lookup", fallbackMethod = ...)`를 붙인다. 자동 조회·수동 대사·callback이 이 계약을 호출한다.
- 순수 분류 정책으로 관찰 결과의 회로 분류와 자동 재조회 여부를 따로 판단한다. HTTP client의 정상 반환을 전부 회로 정상으로 세지 않는다.
- 조회 전 확보한 Outcome/Rejection을 조회 요청에 포함해 알려진 종결·존재 충돌을 제외한다. 원래 관찰은 그대로 저장 TX에 전달한다. 조회 도중 새 저장으로 발견된 충돌을 회로 표본에 소급 반영하지 않는 승인 한계는 [02](trade_off/02-lookup-circuit-breaker.md)에 기록한다.
- 회로 실패는 결과 predicate로 연결한다. 집계 제외 관찰은 원래 관찰을 가진 전용 내부 예외로 프록시 밖에 전달하고, ignore 설정과 구체적인 fallback으로 원래 관찰을 복원하는 방향이다. 회로 차단은 `UNAVAILABLE`의 새 차단 사유로 반환한다. 넓은 예외 fallback으로 구현 오류를 숨기지 않는다.
- 실패/제외/정상/느림의 구체적인 표와 수치는 [02](trade_off/02-lookup-circuit-breaker.md)를 따른다. 제외는 느림/분모에도 포함하지 않는다. 계약 밖 2xx/3xx는 실패 집계·즉시 수동이며 모든 5xx는 실패 집계다.
- Resilience4j `2.3.0`의 `resilience4j-spring-boot3`와 Spring Boot AOP starter를 계획 기준으로 삼는다. 기존 Boot 3.4.4·Java 21은 유지한다. 실제 의존성 해석과 프록시 적용은 구현 시 확인한다.
- 기존 Actuator/Prometheus에 기본 지표를 연결한다. OPEN을 전체 서비스 health의 DOWN으로 연결하지 않는다.

### 자동 실행의 준비와 전달

- 최초 confirm은 기존 PG 생성→관찰 저장→공통 반영 이후 자동 시작을 요청한다. 저장 관찰이 종결/충돌이거나 업무 반영 실패라면 PG 자동 조회로 넘기지 않는다.
- 짧은 준비 TX에서 주문 PAYMENT_PENDING, 시도 STARTED·UNKNOWN/PENDING·충돌 없음, 복구 작업 READY·실행 ID 없음·사용 0회를 검사한다. 기존 작업 claim CAS에 성공하고 commit한 뒤 전달한다.
- 전용 실행기는 고정 스레드 2개·대기 2개·명확한 거절 정책이다. 호출한 confirm 스레드가 자동 작업 전체를 대신 실행하지 않는다.
- 메모리 실행 정보에 시도 키·실행 ID·최초 전달의 단조 시간 기준과 최신 확보 snapshot을 둔다. 서로 다른 주문의 상태를 Spring 싱글턴 필드나 공용 가변 값에 보관하지 않는다.
- 명확한 접수 거절만 TX 밖에서 100ms 뒤 같은 ID로 전달을 추가 1회 시도한다. 재전달 전 완료/인계/시간을 확인한다. 재거절은 현재 ID/회차에 한해 수동 표시한다. 접수 여부가 불명확하면 재전달하지 않는다.
- 전달 전에 종료되거나 큐에서 기다리다 종료되면 RUNNING·사용 0회가 남을 수 있다. 재시작 시 자동 실행하지 않으며 담당자가 기존 수동 경로로 인계한다.

### 자동 조회 한 회차와 대기

- 별도 application Spring 빈의 공개 메서드에 `@Retry(name = "payment-auto-lookup", fallbackMethod = ...)`를 적용한다. 실행기는 UseCase 계약을 통해 프록시를 호출한다.
- 각 회차 시작에 저장된 최신 주문/시도/작업과 현재 실행 ID를 확인한다. 이미 완료·충돌·수동 인계·종결 관찰이 있으면 새 PG 조회를 시작하지 않는다. 저장된 종결 결과는 필요하면 공통 업무 반영으로 마무리한다.
- 대기 중 예산이 끝났다면 새 조회/회차 소비 없이 수동 표시한다. 실행 권한을 잃은 옛 작업은 새 소유자를 수동으로 바꾸지 않는다.
- 짧은 별도 TX에서 현재 RUNNING·실행 ID·기대 사용 횟수와 잔여 회차를 조건으로 consumeRound를 commit한다. commit 뒤 HTTP 전에 종료돼도 사용 회차를 복구하지 않는다. commit 결과가 불명확하면 상태 확인 없이 HTTP를 시작하거나 같은 회차를 재소비하지 않는다.
- **회차 commit 뒤 재확인:** 짧은 별도 TX에서 현재 실행 ID·RUNNING 상태·이번에 확보한 사용 회차·완료 여부를 다시 읽는다. 완료했거나 수동 인계 등으로 권한을 잃었다면 새 PG 조회를 시작하지 않는다. 확인 TX는 공유 조회 프록시 호출 전에 끝내 회로 집계와 HTTP 구간에 DB 대기/잠금을 포함하지 않는다.
- **PG 조회 직전 시간 재검사:** 위 DB 재확인이 끝난 뒤 공유 조회 프록시에 진입하기 직전에 최초 전달부터의 단조 시간 기준 30초 예산을 다시 확인한다. 만료면 새 PG HTTP를 호출하지 않고 이미 commit한 사용 회차는 유지한다. 미완료이고 현재 실행 권한이 유지된 경우에만 원래 실행 ID·확보 회차로 조건부 수동 표시하며, 인계받은 새 실행의 상태는 바꾸지 않는다.
- 재확인 DB 조회가 실패하면 기존 DB 확인 실패 정책대로 자동 반복을 종료하고 PG를 호출하지 않는다. 확인 직후 인계되는 경합까지 완전히 막는 취소 프로토콜은 추가하지 않는다. 이미 시작한 조회의 검증 사실 저장은 허용하고 진행/실패 표시는 기존 실행 ID·확보 snapshot으로 보호한다.
- 위 재확인을 통과한 경우 공유 조회 프록시를 한 번 호출한다. 관찰을 별도 TX로 commit한 뒤 기존 공통 반영을 호출한다. 저장/업무 오류를 PG 조회용 Retry 예외로 바꾸지 않는다.
- 계속 확인할 사유에만 전용 재조회 예외를 사용하고 Retry는 이 예외만 반복한다. 그 예외에는 이번에 확보한 실행/회차와 메모리 시간 예산을 연결한다. 즉시 수동 사유·완료·권한 상실은 반복하지 않는다.
- Retry의 최대 시도는 4회이며 DB 한도가 최종 기준이다. 사용자 정의 간격으로 1/3/10초＋지터 ±20%를 계산하고 대기 전후 시간 예산을 검사한다. 남은 시간까지만 기다린 경우 다음 조회를 일찍 실행하지 않고 예산 소진으로 중단한다. Retry-After는 해석하지 않는다.
- 대기 계산은 전용 재조회 예외에 연결한 실행별 정보로 수행한다. 전체 간격을 기다릴 시간이 부족하면 해당 실행의 종료 flag를 설정하고 남은 시간만 대기한 뒤 다음 진입에서 회차 소비 없이 종료한다. 이미 만료됐다면 전용 예산 중단 예외로 끝낸다. 음수 간격을 종료 신호로 사용하지 않으며 fallback도 재조회 소진/예산 중단 등 필요한 타입으로 한정한다.
- 회로 차단은 이미 commit한 회차를 유지하고 즉시 수동 처리한다. HTTP 0회/회로 표본 0회다. 최대 횟수 소진·예산 소진·명확한 오류는 원래 실행 ID와 확보 회차로 수동 표시한다.
- 소진/fallback에서도 현재 상태를 읽어 이미 완료된 업무를 존중한다. 표시 실패는 로그에 남기고 DB 미완료 기록을 보존하며 실패를 새 결제로 우회하지 않는다.
- 회차 commit 미확인·관찰 저장 실패·상태 확인 실패는 해당 자동 반복을 종료한다. 새 TX로 상태를 확인하고 원래 실행/회차의 조건부 수동 표시만 시도하며, 확인 자체가 불가능하면 로그와 미완료 기록을 남기고 PG HTTP를 추가하지 않는다. 최신 다른 실행의 snapshot을 자기 권한으로 사용하지 않는다.

### 저장된 결과의 내부 반영 재시도

- 현재 ApplyOrderPaymentFacade는 업무 예외를 잡아 수동 표시하므로, 그 안에서 별도의 `@Retry(name = "payment-application")` Spring 빈을 호출하도록 나눈다. 최초 업무 시도 snapshot은 반복 전에 고정한다.
- 새 빈은 기존 ApplyOrderPaymentService의 REQUIRES_NEW 공개 진입점을 호출한다. Retry 대기와 다음 TX를 같은 트랜잭션으로 감싸지 않는다.
- 실제 연결은 RetryOrderPaymentService의 공개 @Retry 진입점과 ApplyOrderPaymentService의 업무 TX다. PaymentApplicationTransaction이 각 TX 안에서 afterCompletion을 등록하고 STATUS_ROLLED_BACK을 확인한 뒤에만 재시도를 허용한다. MySqlPaymentApplicationFailureClassifier는 application 계약을 구현해 같은 SQL 예외의 1213/40001을 확인한다. 완료 확인이 없거나 UNKNOWN이면 추가 반영하지 않는다.
- 예외 원인의 MySQL 오류 1213·SQLSTATE 40001과 전체 rollback 근거를 확인한 데드락만 100ms 뒤 추가 1회다. 이를 식별하는 구현은 infrastructure에 두고 application 계약을 통해 사용한다.
- 연결 끊김·commit 불명확·잠금 대기 timeout·무결성/업무 오류·재고 반환 overflow는 재시도하지 않는다. commit 불명확은 새 TX로 저장 상태부터 읽는다.
- 업무 commit 불명확은 현재 R02의 일반 실패 경로인 표시→조회와 구분한다. 새 TX의 상태 조회를 먼저 하고 완료면 완료를 우선한다. 미완료일 때만 최초 업무 snapshot으로 실패 표시하고 현재 상태를 확인한다. 상태 조회가 불가능하면 표시/재반영을 추측해서 반복하지 않는다.
- 최종 실패 표시는 최초에 확보한 snapshot을 사용하고 새 소유자의 ID/회차로 바꾸지 않는다. 표시 실패 뒤에도 현재 상태를 새 TX로 읽는 R02 보완을 유지한다. 이미 완료됐다면 완료 상태가 우선한다.
- 이 Retry는 PG 생성/조회·자동 조회 회차·조회 회로 표본을 추가하지 않는다. PG 관찰 저장이나 진행 TX까지 범위를 넓히지 않는다.

### 조회·로그·지표

- 기존 PaymentAttemptView의 필드 구조를 유지한다. 기존 reason 필드에 회로 차단·시간 예산 소진·실행기 거절·계약/요청 오류를 구분할 최소 enum을 추가하고 DB 문자열 컬럼/기존 값 호환을 확인한다.
- 주요 사유 분류는 자동 작업과 수동 대사에 함께 연결한다. 수동 대사가 새 회로 차단/계약 오류까지 LOOKUP_UNAVAILABLE 하나로 뭉뚱그리지 않도록 기존 조회 결과를 분류한다.
- 미확인에서는 기존 PAYMENT_PENDING과 재고 차감을 유지한다. 완료 응답/늦은 충돌·고객/관리자 URL·소유권 404·관리자 fixture 계약을 유지한다.
- 로그는 시도/주문/실행 ID·회차·HTTP 상태·사유·반영 결과를 추적한다. 카드/응답 원문은 기록하지 않는다.
- 회로 상태·성공/실패/차단과 Retry 기본 지표를 기존 Prometheus에서 확인한다. 지표 태그에 주문/시도/실행 ID를 넣지 않는다. Retry 지표를 결제 성공률이나 실제 HTTP 수로 해석하지 않는다.
- 전용 큐에서 대기하는 작업의 정확한 만료 시각을 별도 타이머로 갱신하지 않는다. 실행 차례가 오면 예산을 검사한다. RUNNING이 현재 HTTP 실행 중이라는 뜻은 아니다.

## 커밋과 필요한 최소 검증

기능 커밋마다 비즈니스·사용자 경험·기술 리뷰를 받고 중요 수정은 구체안을 사용자에게 확인한다. 관련 검사를 통과한 뒤 명시적으로 staging하고 기존 `type: 한글 요약`과 한글 bullet 본문을 사용한다. 각 커밋은 컴파일/관련 검사 Green을 유지한다.

| 순서 | 커밋 주제 | 구현 범위 | 필요한 대표 검증 |
|---|---|---|---|
| 1 | `feat: PG 조회 회로와 확인 불가 처리 연결` | 분류 정책·조회 계약/프록시·Resilience4j 의존성/설정·차단 사유·수동/callback 공유 회로·기본 지표 | 표 기반 분류, 실제 Spring 프록시의 정상/실패/제외와 HALF_OPEN, OPEN의 실제 HTTP 0회/수동 조회 최대 1회, create는 회로 밖 |
| 2 | `feat: 결제 업무 반영의 데드락 재시도 추가` | 별도 업무 Retry 빈·데드락 식별·최초 snapshot 보존·최종 실패 후 상태 읽기 | 데드락 rollback 뒤 새 TX로 성공·추가 1회 한도, 다른 오류 0회, 대기 중 인계/완료가 늦은 실패 표시로 덮이지 않음, PG 호출 증가 없음 |
| 3 | `feat: 제한된 자동 결제 조회와 실행기 연결` | 최초 confirm 연결·claim TX·회차 TX·commit 후 권한/시간 재확인·2/2 실행기·Retry/지터/30초·수동 사유·로그 | 실제 프록시 최대 4회/중간 종료, 1/3/10초 지터 경계, 가짜 시간의 큐/대기 만료, 회차 commit 대기 중 만료/인계 시 HTTP 0회·사용 회차 유지, 명확한 접수 거절 추가 1회, confirm 재요청 미시작, callback/수동 인계 뒤 옛 조회/표시 중단 |
| 4 | `test: 결제 회복 통합 검증과 제출 자료 정리` | 중복하지 않는 실제 PG/DB 장애·재시작 실험, 상태/운영 문서·기술 글·결과 | 성공/실패 응답 유실 후 자동 종결, 회차 commit 뒤 종료/같은 DB 재생성 후 자동 HTTP 0회와 수동 복구, 공통 반영 실패 근거 보존, 기존 핵심 회귀와 필수 전체 검사 |

- 분류는 순수 정책의 표 기반 검사로 묶고 모든 상태 조합을 controller/DB에서 반복하지 않는다.
- 프록시 적용은 기존 공유 Spring 테스트 구성에서 확인한다. 단위 검사만으로 애노테이션 적용을 증명하지 않는다.
- 긴 간격/느림/OPEN/HALF_OPEN은 고정된 입력·시간 제어로 확인한다. 운영 설정 값은 설정 검사로 확인하고 긴 실시간 sleep을 사례마다 반복하지 않는다. 라이브러리 전역 sleep 함수를 바꿔 병렬 검사에 영향을 주지 않는다.
- 동시성은 기존 한 번 반영 경계를 재사용하며 회로/재시도 때문에 생기는 인계·늦은 실패 분기만 추가한다. 기대값을 약하게 바꾸거나 검사를 skip하지 않는다.
- DB를 쓰는 변경에는 관련 MySQL 통합 검사, 나머지는 순수 정책/작은 HTTP 서버/기존 fixture를 우선한다. 실제 테스트 이름은 구현할 파일에 맞춰 정한다.
- PR 마무리에는 `:apps:test-pg:check :apps:commerce-api:check`를 한 번 수행한다. 이후 새 변경·실패로 필요한 경우만 반복하고 실제 건수·오류·skip·종료 코드를 남긴다.
- 실제 PG 실험은 기존 payment-lab과 명시 SQL을 사용한다. 외부 거래 수·create/lookup 수·주문/시도/작업/차감 기록·재고·포인트를 함께 확인하고 시작한 자원과 임시 실패 장치를 정리한다.

## 문서와 완료 증거

- 구현 시 states/의 R03 목표와 실제 구현을 구분해 갱신한다. 운영 문서는 기존 조회로 찾는 방법·중단 사유 해석·수동 대사와 Prometheus 확인 예시를 설명한다.
- 기술 글은 저장소 Markdown으로 작성하고, 주제는 ‘결제 실패와 조회 실패를 구분하고 timeout 뒤 재고를 유지한 이유’를 초안 방향으로 삼는다. 실제 구현/실험 근거를 확보한 뒤 작성하며 미검증 결과는 쓰지 않는다.
- result.md는 실제 구현·검증 후 생성한다. 증거는 기존처럼 무시되는 build/ 로그와 커밋할 결과 요약으로 나누며, 원문 두 파일·사용자 JMeter 변경을 보존한다.
- 이번 계획 작성은 기능 구현 승인이 아니며 원격 push/PR 생성도 포함하지 않는다. 계획 리뷰에서 중요한 설계 보완이 나오면 먼저 사용자와 결정한다.

## 계획 검토와 실제 진행

- 비즈니스·사용자 경험 초회 계획 리뷰: 중요 지적 없음. 기술 초회 리뷰의 중요 보완 1건은 사용자가 설명을 확인한 뒤 명시적으로 승인해 위 계획에 반영했다. 작은 명시 보완과 함께 세 관점 재리뷰를 마쳤으며 미해결 중요 지적은 없다. [리뷰 기록](review.md)을 따른다.
- 첫 기능: PaymentLookupPolicy·PaymentLookupGateway/LookupRequest·CircuitPaymentLookupGateway·회로 설정/로그·수동/callback 연결·회로 기본 지표를 구현했다. 생성 경로는 조회 회로 밖이며 아직 자동 조회 작업은 만들지 않는다.
- 기능 리뷰의 중요 지적 1건(알려진 종결/존재 충돌 제외)은 사용자 승인 후 적용했다. 비즈니스·사용자 경험·기술 재리뷰 모두 미해결 중요 지적 없음. 보완 후 관련 검사 76건(실패·오류·skip 0)과 Checkstyle main/test·ArchUnit 8건을 통과했다. ArchUnit은 76건에 포함된다. 실제 Spring 프록시 정상/실패/제외·OPEN 차단·HALF_OPEN 복귀/느림·알려진 충돌 6경계·수동/callback 보존·raw HTTP 계약·기존 confirm을 확인했다. 새 전체 검사나 독립 PG 실험을 수행한 결과는 아니다.
- 두 번째 기능: RetryOrderPaymentService·TX 완료 확인·정확한 MySQL 코드 식별·최초 snapshot 보호·commit 미확인의 상태 우선 조회를 구현했다. 세 관점 기능 리뷰에 중요 지적이 없다. 관련 고유 검사 49건(초회 47건＋테스트 설정 API 수정 후 2건 재검사)과 Checkstyle main/test·ArchUnit 8건이 통과했다. ArchUnit은 49건에 포함된다. PG 호출/회차/표본 증가 없음, 실제 rollback/새 TX와 소진/인계 보호를 확인했다. MySQL 코드 예외 주입 검사이며 실제 DB 경합에서 victim 선정을 재현한 실험은 아니다.
- 세 번째 기능: 최초 confirm 연결·claim/consume TX·commit 후 재확인·2/2 실행기·실행별 지터/30초·중단 사유를 구현했다. 세 관점 기능 리뷰에 중요 지적이 없다. 관련 53건(실패·오류·skip 0)과 Checkstyle/ArchUnit이 통과했고 기본 지표/운영 간격 및 실행기 assertion을 보강한 2건을 재검사해 통과했다. 실제 Retry 프록시 최대4회·중간 종결·차단/요청 오류·저장 종결 재적용·용량·시간/인계/미확인 경계를 확인했다. test 프로필은 confirm 자동 시작만 끄고 컴포넌트/프록시/DB를 직접 검사한다.
- 최종 검증: 독립 PG/commerce의 새 주문 55~68 실험 14건과 필수 전체 검사 509건(PG25/commerce484, 실패·오류·skip 0)을 마쳤다. 유실 뒤 성공/실패·4회 소진·DB 대기/큐 만료·접수 거절·느림 회로/회복·PG 재시작·업무 rollback·회차 commit 뒤 commerce 종료/재시작을 확인했다. 마지막 기능 코드 변경은 없으며 원문/JMeter·POINT 보존과 실험 자원 정리도 완료했다. [결과](result.md)·[재현](verification.md)·[운영](operations.md)·[기술 글](technical-article.md)에 기록했다.
- result.md 작성 완료. PR 설명 초안은 로컬에 준비했으며 원격 PR은 아직 생성하지 않았다.

## 확인한 기술 근거

- [Resilience4j 2.3.0](https://github.com/resilience4j/resilience4j/releases/tag/v2.3.0), [Spring Boot 애노테이션/지표](https://resilience4j.readme.io/docs/getting-started-3).
- [2.3.0 RetryAspect](https://github.com/resilience4j/resilience4j/blob/v2.3.0/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/retry/configure/RetryAspect.java), [CircuitBreakerAspect](https://github.com/resilience4j/resilience4j/blob/v2.3.0/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/circuitbreaker/configure/CircuitBreakerAspect.java), [RetryImpl](https://github.com/resilience4j/resilience4j/blob/v2.3.0/resilience4j-retry/src/main/java/io/github/resilience4j/retry/internal/RetryImpl.java): 구현 시 결과/예외 분류·대기·fallback의 실제 동작을 최소 검사로 확인한다.
