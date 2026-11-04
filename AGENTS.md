# 2주차 작업 규칙

아래 2주차 규칙은 당시 기록이며, 현재 주차의 적용 안내와 기술 규칙을 함께 따른다. 규칙의 이유와 예시는 [개발 컨벤션](docs/week2/conventions.md)을 기준으로 한다.

## 5주차 적용 안내

2026-10-23 최종 점검: R03 [PR #26](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/26)까지 병합한 기준은 `volume-5/main`의 `b70d83b`다. [5주차 최종 점검](docs/week5/final-audit.md)을 먼저 읽는다. 병합 트리는 검증된 `8e2e92f`와 같으며 전체 검사509건/실제 R03 실험14건의 기존 근거를 대조했다. 발견한 PG 실패 반환 캐시 검증 공백은 사용자 요청으로 commit/rollback2건을 추가해 보완했다. 캐시16건/ArchUnit8건의 관련24건과 테스트 Checkstyle을 통과했고 기능 수정은 없다. 반환 캐시 보완과 점검 문서는 e200e78로 volume-5/main에 게시했다. 아래 PR 생성 예정·미정 표현은 당시 진행 기록이며 현재 범위는 최종 점검과 인계 문서를 우선한다.

다음 작업은 [인계 문서](docs/week5/handoff.md)에서 현재 브랜치·완료 검증·남은 범위를 먼저 확인한다.

처음 읽는 개발자용 [결제 장애 대응 안내](docs/week5/resilience/README.md)를 추가했다. [서킷브레이커](docs/week5/resilience/circuit-breaker.md)·[폴백 전략](docs/week5/resilience/fallback.md)은 현재 구현을 설명하고 기존 [요청 상태 모델](docs/week5/states/payment.md)을 재사용한다. 고객 confirm/reconcile의 본문에는 completed가 없으며 callback 응답만 이를 노출한다. 해당 문서 표현을 실제 controller/View에 맞춰 바로잡았다. 문서 변경은 기능/테스트 재검사나 별도 기능 리뷰 대상으로 확대하지 않는다.

현재 준비 범위·진행 순서·완료 조건은 [5주차 전체 요구사항](docs/week5/total_requirement.md)을 따른다.
기준 브랜치는 `volume-5/main`이며, `volume-4/main`의 `9eff402`에서 시작한다.
PR은 아래 세 개로 나눈다. 각 작업 브랜치는 작업 시작 시 최신 기준 브랜치에서 생성하고 `volume-5/main`을 대상으로 한다.

- R01 `volume-5/r01-pg-payment-foundation`: 테스트 PG·HTTP adapter·결제/차감 기록 모델.
- R02 `volume-5/r02-pg-order-payment`: 재고 선차감·PG 주문 전환·멱등성·callback·대사.
- R03 `volume-5/r03-payment-resilience`: 조회 회복 전략·통합 장애 검증·제출 정리.

R01은 로컬 구현·검증 후 [PR #24](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/24)로 병합됐다(`4b81541`). [R01 결과](docs/week5/r01-pg-payment-foundation/result.md)에 전체 검사 384건·실연결·보존과 승인 보완을 기록했다. payment-lab은 commerce8080/PG8081/commerce관리8082와 INFO 콘솔을 사용한다.
R02도 이 병합 기준에서 시작해 **로컬 구현·검증을 완료했다.** [R02 결과](docs/week5/r02-pg-order-payment/result.md)에 전체 검사 432건(PG25/commerce407, 실패·오류·건너뛰기0), 독립 JVM 실험 10개와 기존 POINT/사용자 파일 보존을 기록했다. 준비/공통 업무 TX·주문 CAS·실제 PG confirm·고객/관리자 조회·단일 수동 대사/인계·동기 callback을 연결했다. 기능마다 세 관점 리뷰와 중요 수정 승인·재리뷰를 마쳤으며 [리뷰 기록](docs/week5/r02-pg-order-payment/review.md)에 미해결 중요 지적은 없다. 이번 최종 검증은 기능 변경 없이 완료했다. R02는 사용자 요청으로 게시한 [PR #25](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/25)가 병합됐다(`42c22c2`). R03의 공유 조회 회로·fallback·내부 반영 데드락 Retry·제한된 자동 조회/실행기를 구현했다. 2026-10-23 실제 PG 장애/재시작 실험 14건과 필수 전체 검사 509건(PG25/commerce484, 실패·오류·skip0)까지 완료했다. 기능 기준은 `ae46776`이며 최종 검증의 기능 수정은 없다. [R03 결과](docs/week5/r03-payment-resilience/result.md)·[운영 안내](docs/week5/r03-payment-resilience/operations.md)를 따른다. 원격 PR은 후속 요청 범위다. [상태 문서](docs/week5/states/total.md)는 R01/R02 구현과 R03 목표를 구분한다.
2026-10-22 R03 착수: 최신 기준 `42c22c2`에서 `volume-5/r03-payment-resilience`를 생성했다. 주요 정책을 확정하고 [상세 구현 계획](docs/week5/r03-payment-resilience/plan.md)을 작성해 비즈니스·사용자 경험·기술 초회 계획 리뷰를 마쳤다. [리뷰 기록](docs/week5/r03-payment-resilience/review.md)의 회차 commit 뒤 권한/시간 재확인 보완은 사용자 승인 후 계획에 반영했고 세 관점 재리뷰도 마쳤다. 계획의 미해결 중요 지적은 없다. 2026-10-23 첫 기능인 PG 조회 회로·fallback·수동/callback 연결·기본 지표를 구현했다. 기능 리뷰의 알려진 종결/존재 충돌 제외 보완은 사용자 승인 후 적용했고 세 관점 재리뷰도 중요 지적 없이 완료했다. 관련 검사 76건(실패·오류·skip 0)과 Checkstyle/ArchUnit이 통과했다. 이어서 내부 반영 데드락 Retry도 구현했다. 실제 rollback 완료와 MySQL 1213/40001을 함께 확인한 경우만 새 TX로 100ms 뒤 추가 1회이며, commit 미확인은 상태 우선 조회·최초 snapshot 보호를 유지한다. 세 관점 기능 리뷰에 중요 지적이 없고 관련 고유 검사 49건(47건 초회 통과＋설정 확인 API 수정 후 2건 재검사 통과)·Checkstyle/ArchUnit이 통과했다. 자동 조회·실행기도 구현했다. 세 관점 기능 리뷰에 중요 지적이 없고 관련 53건과 Checkstyle/ArchUnit이 통과했다. 기본 지표/운영 간격과 실행기 검사를 보강한 2건도 재검사했다. test 프로필은 confirm 자동 시작만 false이며 자동 컴포넌트/프록시/DB는 별도 핵심 검사한다. 2026-10-23 실제 PG 장애/재시작 실험 14건과 필수 전체 검사 509건(PG25/commerce484, 실패·오류·skip0)까지 완료했다. 기능 기준은 `ae46776`이며 최종 검증의 기능 수정은 없다. [R03 결과](docs/week5/r03-payment-resilience/result.md)·[운영 안내](docs/week5/r03-payment-resilience/operations.md)를 따른다. 원격 PR은 후속 요청 범위다. [결정 목록](docs/week5/r03-payment-resilience/trade_off/total_trade_off.md)·[회로 집계](docs/week5/r03-payment-resilience/trade_off/02-lookup-circuit-breaker.md)·[연결/운영 범위](docs/week5/r03-payment-resilience/trade_off/03-resilience-library-wiring.md)가 현재 기준이다. 자동 조회 Retry와 공유 조회 CircuitBreaker는 Resilience4j 애노테이션으로 통일해 기존 Spring Retry 선택을 대체했다. 최초 confirm만 시작·claim commit 후 전달·전용 2스레드/큐2·최초＋추가3회·1/3/10초±20%·최초전달부터30초·회로 차단 즉시 수동·내부 반영 데드락만 추가1회/100ms·기존 조회＋로그＋기본 지표를 확정했다. 회로 수치와 HTTP 분류는 링크 문서를 따른다. 준비 문서나 계획 리뷰를 구현·검사 완료로 기록하지 않는다.
2026-10-22 R02 설계 결정: 준비 진입은 주문 상태 DRAFT→PAYMENT_PENDING CAS로 선점하고, 공통 결과 반영도 PAYMENT_PENDING→CONFIRMED/PAYMENT_FAILED CAS 성공자가 맡는다. 결과 반영은 결제 시도 행을 짧게 잠가 저장된 결과/충돌을 확인하며 관련 주문 기록·재고·작업 변경을 같은 TX에서 보호한다. 상품 재고 잠금·복구 작업 CAS·관찰 저장 경계는 유지한다. callback은 요청 안의 동기 처리이며 PG 권위 조회는 TX 밖, 관찰 commit 후 공통 업무 TX를 호출한다. 별도 callback 접수 실행기는 추가하지 않는다. 수동 대사는 저장 종결 근거가 있으면 조회 없이 반영하고, 그 외 요청당 PG 조회 최대 1회·미완료 202다. 자동 회차를 소비/초기화하거나 새 자동 대기 작업을 만들지 않는다. 재고 복구 한도 초과는 업무 전체 rollback·PG 실패 보존·수동 처리로 정했다. 관리자 반환 예정량 집계 제한은 추가하지 않는다. 준비·공통 반영·수동 대사·동기 callback API를 연결했다. callback은 초기 실행 비교값으로 늦은 실패 표시를 보호하며 작업 인계·회차 소비를 하지 않는다.
2026-10-22 고객 소유권 결정: confirm/고객 상세/reconcile에 기존 X-USER-ID와 저장된 주문 userId 비교를 추가한다. 과거 POINT 고객 상세도 포함하며 타인 주문은 없는 주문과 같은 404·ORDER_NOT_FOUND·메시지다. 주문 상태·기존 결제 결과 반환이나 CAS/재고/PG/작업 변경 전에 검사한다. 실제 로그인·Spring Security는 추가하지 않는다. 주문 관리자 조회·미완료 목록·운영자 대사는 기존 X-USER-ID=1 관리자 fixture를 확인한다. 고객 경로는 1번도 본인 주문만 처리하며 상품 등 다른 관리자 API 변경·역할 테이블은 포함하지 않는다. 이 결정은 과거 2주차의 본인 검사 제외 정책보다 우선한다. confirm과 고객 GET 소유권 및 주문 관리자 GET 접근은 구현했다. 수동 대사의 접근/처리와 고객 헤더를 사용하지 않는 callback 권위 확인도 연결했다.
2026-10-21 최신 사용자 요청에 따라 **기능 동작을 바꾸는 구현만 비즈니스·사용자 경험·기술 서브에이전트 리뷰**를 받는다. 동작 변경 없는 문서·빌드 구성·테스트 전용 변경은 별도 리뷰하지 않는다. 최신 사용자 요청에 따라 중요한 기능 리뷰 수정은 구체안을 사용자에게 확인받고 진행하며 사소한 보완까지 승인 대상으로 확대하지 않는다. 테스트는 핵심 상태/멱등성·저장/경합·HTTP 오류·보존에 필요한 최소 사례만 작성하고 변경마다 관련 검사만 실행한다. 필수 전체 검사는 PR 마무리에 한 번 실행하고 이후 변경/실패로 필요해진 경우만 반복한다. 기존 검사 기준은 완화하지 않는다. 커밋은 Green 이후 최소 기능 단위·`type: 한글 요약`·명시 staging을 유지한다.
구현 계획의 기본값은 독립 apps:test-pg·127.0.0.1:8081·loopers_test_pg/test_pg·보존용 payment-lab 프로필과 validate/명시 SQL이다. 시도 키는 표준 UUID(공백 거절·소문자 정규화), 테스트 조건은 향후 confirm의 요청별 옵션(생략 NORMAL_SUCCESS)이다. enum·옵션 필드·주기·프리셋 등은 계획을 따른다. 아래 이전 논의의 미정 목록보다 최신 계획의 구체값을 우선하며 기능 완료로 해석하지 않는다.
후속 [학습용 최소 보완](docs/week5/r01-pg-payment-foundation/trade_off/10-learning-scope-completion.md)을 확정했다. 검증 결과/확정 거절 사실은 기존 pay 시도에 짧은 TX로 먼저 commit하고 공통 업무 반영 TX는 분리한다. 저장된 종결 결과의 미반영은 재조회 없이 재적용할 수 있다. 실효 테스트 조건은 고정 필드로 보존·비교한다. PG는 DB 예정 시각/결과를 주기 조회해 완료하고 재시작 실험용 프로필은 테이블을 보존하며 초기화는 명시 실행한다. 수동 대사는 실행 ID 조건부 교체로 옛 진행 갱신을 차단한다. commerce의 지터/다음 예약 정보는 영속화하지 않는다. 정확한 필드·프로필/초기화 파일·조건식·주기 등은 상세 설계 대상이다.
사용자 추가 조건으로 테스트 PG는 로컬 독립 실행 서버이며 commerce가 실제 HTTP로 연결한다. 거래는 기존 MySQL의 PG 전용 스키마에 저장하고 PG 재시작 후에도 보존한다. 메모리 저장 선택은 철회됐다.
재고는 먼저 차감해 commit하고 PG를 TX 밖에서 호출한다. 성공 시 차감 상태 유지, 확정 실패 시 한 번 복구하며 결과 미확인에서는 유지한다.
[HTTP 호출](docs/week5/r01-pg-payment-foundation/trade_off/03-http-client-settings.md)은 RestClient + Apache HttpClient 5 직접 호출이며 자동 retry/redirect를 끈다. 초기값은 연결 1초·연결 풀 대기 1초·응답 대기 2초다. 전체 호출의 절대 제한을 뜻하지 않는다. [adapter](docs/week5/r01-pg-payment-foundation/trade_off/04-adapter-fixture.md)는 검증한 관찰/확인 불가 사유를 결과값으로 구분해 반환한다. 실제 PG는 정상·지연·유실·DB 보존을, 작은 테스트용 HTTP 서버는 불완전 응답 등을 검증한다. 재조회/회로 연결에서 결과값 분류가 필요하며 상세 타입·버전·읽기 제한 적용은 후속 설계/검증 대상이다.
결제 시도 키는 [전체 결제 시도에서 전역 유일](docs/week5/r01-pg-payment-foundation/trade_off/02-idempotency-request-contract.md)하다. 고객이 달라도 같은 키를 다른 주문에 재사용할 수 없다. 주문당 한 시도 제약은 별도로 유지한다. 실제 적용할 PG 테스트 조건도 비교하며 다르면 충돌로 처리한다. 사용자 요청으로 카드 입력은 재요청 비교에서 제외하고 같은 주문·키·저장 금액·실제 조건이면 기존 상태를 반환한다. 카드 기반 HMAC/해시 저장·비밀키 관리는 범위 밖이며 카드 원문 비저장/로그 금지·형식 검증은 유지한다. 키는 표준 UUID를 소문자 36자로 정규화하며 조건 필드/기본값은 최신 구현 계획을 따른다.
확인한 결제 결과와 복구 진행을 나누어 관리한다. 수동 전환·조회 실패로 마지막 검증 결과를 덮어쓰지 않는다. 정확한 enum·허용 조합·전이는 [상태 설계](docs/week5/r01-pg-payment-foundation/trade_off/05-payment-attempt-state.md)에서 후속 확정한다.
복구 진행·사용 횟수·현재 실행/최근 시각·오류는 [별도 pay 복구 작업 모델·저장소](docs/week5/r01-pg-payment-foundation/trade_off/06-recovery-progress-storage.md)에 둔다. [재고 차감·복구 기록은 mall의 별도 모델](docs/week5/r01-pg-payment-foundation/trade_off/07-stock-adjustment-boundary.md)이다. 확정 실패 반영 시 실제 재고와 복구 기록·관련 주문/결제/작업 변경을 같은 commerce DB의 로컬 TX에서 보호하며 별도 비동기 재고 복구 worker는 두지 않는다.
application 파사드가 모델 간 흐름을 조율한다. [A안](docs/week5/r02-pg-order-payment/trade_off/01-facade-transaction-boundary.md)으로 조율 파사드와 준비/공통 결과 반영용 TX 컴포넌트를 분리하기로 확정했다. 각 TX 진입점은 별도 Spring 빈의 공개 @Transactional 메서드다. 전체 흐름을 단일 TX로 감싸지 않으며 PG HTTP·재조회 대기는 TX 밖에 둔다. callback·수동 대사·자동 재확인은 같은 결과 반영 TX 진입점을 사용한다. 후속 결정으로 관찰 저장 TX를 먼저 commit하고 공통 업무 반영 TX를 호출한다. 실제 클래스명·Context 배치·잠금 순서는 미정이다.
8번은 기존 비관적 잠금 선택을 변경해 [멱등성 키 + 조건부 상태 갱신 + 실행 식별자](docs/week5/r01-pg-payment-foundation/trade_off/08-recovery-claim-coordination.md)를 채택했다. 복구 작업 실행 권한에 명시적 SELECT FOR UPDATE를 필수로 두지 않고 DB의 원자적 갱신 결과와 현재 실행 식별자를 검증한다. DB 쓰기 내부 잠금까지 제거하거나 상품 재고/공통 결과 반영의 보호를 없앤 선택이 아니다. JPA 쓰기 규칙은 유지하며 수동 인계는 실행 ID 조건부 교체로 확정했고 구체적인 조건식/응답·bulk/Entity 정합성은 후속 설계 대상이다. 자동 lease는 보류한다.
후속 사용자 결정으로 정상 실행은 최초 조회+추가 재조회 최대 3회, 간격 1초·3초·10초와 지터 ±20%를 적용한다. 실패/수동 전환 기준은 [재확인 정책](docs/week5/r03-payment-resilience/trade_off/01-reconciliation-retry-policy.md)을 따른다. 정상 실행은 2026-10-22 후속 결정으로 Resilience4j Retry + DB 기록을 사용하고 장애/재시작 뒤 자동 재개는 [운영 담당자 미완료 목록 조회·수동 대사](docs/week5/r02-pg-order-payment/trade_off/06-interrupted-payment-reconcile.md)로 변경했다. [조회 직전에 횟수를 DB에 기록·commit](docs/week5/r03-payment-resilience/trade_off/07-retry-attempt-accounting.md)하며 실제 조회 전 종료돼도 소비를 취소하지 않는다. 목록 GET은 조회 전용, 실제 대사는 명시적인 POST 요청이다. 자동 예산을 새로 부여하지 않으며 PG create 재시도는 금지한다. 하위 HTTP timeout과 자동 조회 30초·내부 반영 추가 1회/100ms는 위 후속 결정으로 확정했다. 세부 오류 매핑·실행기 연결 등은 미정이다.
아래 과거 포인트 주문의 단일 TX 규칙이나 2주차 동시성 제외 규칙을 5주차 PG 경계에 그대로 적용하지 않는다.
소유권·관리자 접근의 적용 범위는 위 2026-10-22 결정을 따른다. confirm·고객 GET·주문 관리자 GET·고객/관리자 수동 대사의 접근 검사를 구현했다.
기존 패키지·계층 의존·순수 도메인·데이터 접근·TDD·Checkstyle·ArchUnit·검사 완화 금지와 사용자 변경 보존 규칙은 유지한다.
준비 문서나 계획을 구현·테스트 완료로 기록하지 않고 `result.md`는 실제 구현·검증 후 작성한다.

## 4주차 적용 안내 · 당시 기록

4주차 제출 범위·진행 순서·완료 조건은 [4주차 전체 요구사항](docs/week4/total_requirement.md)을 따른다.
기준 브랜치는 `volume-4/main`이며, 3주차 최종 병합 커밋 `72ae005`에서 시작한다.
아래 2주차 작업 순서·범위와 3주차 문서는 당시 기록이다. 4주차의 브랜치·캐시 선택·경쟁 검증 범위는 4주차 문서를 우선한다.
패키지·계층 의존·순수 도메인·데이터 접근·TDD·Checkstyle·ArchUnit·변경 금지 규칙은 현재 코드와 후속 변경 사항을 기준으로 유지한다.
HTTP 성능 테스트는 JMeter를 사용하며, 실제 측정 전 수치나 성능 개선을 완료로 기록하지 않는다.
R02의 고객 상세 로컬 캐시·변경 경계·측정은 [승인 계획](docs/week4/r02-product-detail-local-cache/plan.md)을 따른다.
제출 시 캐시는 Caffeine W-TinyLFU 로컬 캐시로 확정했다. Redis는 이번에 도입하지 않기로 한 선택이며 남은 필수 작업이 아니다.
여러 서버의 캐시 공유와 DB 직접 수정의 전파는 지원 범위 밖이다. Redis 장애·복구를 검증했다고 기록하지 않는다.

## 작업 순서

학습 범위와 총 7개 PR의 완료 조건은 [개발 계획](docs/week2/development-plan.md)을 따른다.
기준 브랜치는 `volume-2/main`, 작업 브랜치는 `volume-2/pr-번호-작업명`이다.
앞 PR을 검증·병합한 뒤 최신 기준 브랜치에서 다음 브랜치를 만들며 작업 브랜치끼리 직접 병합하지 않는다.
PR 01은 계획 정리·공통 기반·사용자 입력을 함께 다룬다. PR 내부에서는 작은 기능 단위로 TDD와 커밋을 진행한다.
동시성 구현·검증은 다음 학습으로 미룬다. 잠금·버전 검증·재시도·동시 요청 테스트는 이번 범위에서 제외한다.
순차 재확정 거절, DB 유일성 제약, 단일 요청의 전체 롤백은 유지한다.
과제 원문의 인증·인가 요구는 이번 학습 범위에서 제외한다. Spring Security·ADMIN·CSRF·본인 여부 검사를 구현하지 않는다.
`X-USER-ID`는 fixture 사용자 입력이다. 필요한 API에서 누락·형식 오류는 400, 없는 사용자는 404로 처리한다.
좋아요 목록은 경로 userId, 주문 상세·확정은 orderId를 사용한다. 주문 결제 대상은 저장된 userId를 따른다.
상품·브랜드 조회와 관리자용 기능은 사용자 헤더를 요구하지 않는다. API별 입력은 설계 계약을 따른다.

1. 구현 전에 요구사항과 API 계약에서 정상 결과, 대표 오류, 상태 유지 조건을 확인한다.
2. 미정 정책은 임의로 결정하지 않는다. 설계 문서에 질문과 영향을 기록하고 사용자에게 확인한다.
3. 한 번에 하나의 작은 기능을 다룬다. 변경할 책임, 파일 범위, 관련 테스트를 먼저 밝힌다.
4. 대표 도메인 규칙은 테스트를 먼저 작성하고 Red, Green, Refactor 결과를 확인한다.
5. 변경 후 diff와 관련 테스트를 확인하고, 기능이 끝나면 Checkstyle, ArchUnit, 모듈 검사를 실행한다.
6. Green 이후 관련 테스트가 통과한 작은 기능 단위로 커밋한다. 커밋 시점과 메시지 기준은 개발 계획을 따른다.

## 구조와 의존

- User·두 사용자 fixture는 PR 01, 사용자별 초기 잔액 0의 Point는 PR 04에 연결하며 기존 잔액을 초기화하지 않는다.
- PR 02에서 Shopping 소유의 Like 관계·유일성·집계 테이블과 주기 집계를 준비하고 등록·취소는 PR 03에서 연결한다.
- PR 05에서 Pay 소유의 OrderBill 저장·조회 구조를 먼저 준비하고 실제 결제 기록 생성은 PR 06에서 연결한다.
- 선행 저장 구조는 fixture로 검증하며 Context 소유권을 유지한다. 준비용 공개 API·임시 상수 응답을 추가하지 않는다.

- (2주차 당시 규칙, 역사적 기록) 패키지는 `interfaces`, `application`, `domain`, `infrastructure` 아래 Context → 기능 순서로 나눴다.
  Context 이름은 `mall`, `shopping`, `ordering`, `pay`였다. 예: `domain.mall.product`.
- 이후 `volume-3/refacto`에서 패키지 구조가 Context → 종류(kind) 순서로 바뀌었다. domain·application·interfaces는
  `<layer>.<context>.<종류>` 순서를 따른다. 예: `domain.mall.model`, `application.mall.query`,
  `interfaces.api.mall.controller`. infrastructure만 종류가 먼저다: `infrastructure.<종류>.<context>` 순서를 따른다.
  예: `infrastructure.persistence.mall.entity`. 현재 구조와 결정 배경은 [CLAUDE.md](CLAUDE.md)와
  [docs/refactor/](docs/refactor/)(`plan.md`, `context-notes.md`, `result.md`)를 참고한다.
- `interfaces`는 HTTP 입력과 응답 변환을 담당하며 `infrastructure`에 직접 의존하지 않는다.
- `application`은 유스케이스 순서와 트랜잭션을 조율하며 `interfaces`와 `infrastructure` 구현에 의존하지 않는다.
- 신규 `domain`은 순수 Java로 상태·업무 규칙·repository 계약을 소유한다. Spring·JPA·HTTP와 다른 계층에 의존하지 않는다.
- `infrastructure`는 domain 저장 계약과 application QueryDao·조회 타입을 사용한다. application 구현 서비스에는 의존하지 않는다.
- HTTP 정책이나 업무 규칙을 infrastructure에서 중복 구현하지 않는다.
- GET은 조회 전용 Controller → application QueryDao 계약 → infrastructure 구현으로 연결한다. 조회 구현은 QueryDSL
  Projection을 사용한다. 쓰기는 JPA, JdbcClient는 좋아요 집계 같은 배치 작업에만 사용하며 테스트 코드도 쓰지 않는다(3주차 후속 R03·R08에서 변경, 운영 코드는 `LayerArchitectureTest`가 검사한다).
- DAO는 같은 DB의 Context 간 조인과 조회 모델 조합을 담당한다. Controller는 입력·404·ApiResponse 포장을 담당한다.
- 조회 모델은 application의 순수 record이며 별도 HTTP Response 복사 없이 반환한다. domain은 HTTP DTO를 알지 못한다.
- `@XUserId` resolver는 UserQueryDao.findById로 사용자 존재를 검사하고 ID를 반환한다. 형식 오류 시 DAO를 호출하지 않는다.
- 경로 userId는 좋아요 조회 Controller에서 같은 DAO로 검사한다. 쓰기 Service는 사용자 존재를 재검사하지 않는다.
- 쓰기 Service를 직접 호출하는 쪽은 존재하는 사용자 ID를 제공한다. 사용자 검증을 인증·인가로 해석하지 않는다.

## Java 코드

- Java 21과 기존 코드 스타일을 사용하고 `.editorconfig`의 본문 최대 130자와 테스트 파일 예외를 따른다.
- 별표 import와 미사용 import를 허용하지 않는다.
- 의존성은 생성자로 주입한다. Spring 구성요소에는 기존 관례에 맞춰 `@RequiredArgsConstructor`를 사용할 수 있다.
- 도메인 `Product`와 저장 객체 `ProductJpaEntity`를 분리한다. domain은 JPA BaseEntity를 상속하지 않는다.
- 도메인은 숨긴 생성자와 `create` / `restore`로 유효한 상태를 구성하고, 공개 setter 없이 의미 있는 행동으로 변경한다.
- Money·Stock처럼 규칙이 모이는 값부터 값 객체로 분리한다. 실패하는 행동은 메모리 상태도 유지해야 한다.
- 행동별 `ConfirmOrderUseCase` 인터페이스와 `ConfirmOrderFacade` 구현체(컨텍스트 서비스를 순서대로 조율하는 `facade`)를 두고 `execute`로 실행한다.
- 쓰기는 Request → Command → Result → Response, 조회는 Request → Criteria → 조회 record → ApiResponse를 사용한다.
- 쓰기 트랜잭션은 application Service의 `execute`, 조회 readOnly 트랜잭션은 DAO 구현의 공개 메서드에 둔다.
- DAO 상세 조회는 Optional을 반환하며 Controller가 기존 오류로 변환한다. 조회 UseCase·Service는 만들지 않는다.
- 쓰기 성공 응답은 Result를 유지한다. 상품 응답의 likeCount만 DAO로 저장된 집계 값을 읽는다.
- 도메인 변경 후 repository의 `save`를 명시한다. 주문 확정의 모든 변경·기록은 단일 트랜잭션으로 저장한다.
- infrastructure의 전용 EntityMapper로 변환한다. repository는 기존 Entity를 확보하고 Mapper는 저장 메타데이터를 보존한다.
- Mapper 인터페이스·변환 라이브러리는 추가하지 않는다. Mapper에는 업무 판단을 넣지 않는다.
- Request는 형식·구조, domain은 업무 규칙, application은 쓰기의 대상·사용자별 데이터·객체 간 조건을 검사한다.
- domain은 DomainException·업무 코드로 실패를 표현하고 interfaces가 기존 HTTP·ApiResponse 계약으로 변환한다.

## 테스트

- 핵심 업무 규칙은 TDD로 진행한다. domain 단위 테스트는 Spring·DB 없이 정상·경계·실패·실패 후 상태를 엄격히 검증한다.
- 0·정확한 필요량·1 부족·최댓값·계산 초과, 중복 품목·삭제 상태·재확정을 포함한다.
- UseCase 단위 테스트는 실제 도메인과 mock repository/DAO로 협력·사용자별 데이터·순서·실패 시 후속 처리 중단을 검증한다.
- DAO는 실제 DB로 조회·필터·페이지·집계를 검증하고 resolver는 정상·400·404·잘못된 입력의 DAO 미호출을 검증한다.
- 전체 롤백은 실제 DB 통합 테스트로 검증한다. 복잡한 Mapper 변환은 스냅샷·품목·메타데이터 보존을 확인한다.
- repository 통합 테스트는 필요할 때 `flush`와 `clear` 후 저장 값을 재조회한다.
- HTTP 테스트는 실제 Controller, application, repository와 테스트 DB를 연결해 입력·응답·저장 결과를 검증한다.
- JUnit 테스트는 한글 `@DisplayName`, 기능별 `@Nested`, `arrange`, `act`, `assert` 구성을 사용한다.
- 오류 테스트는 예외나 상태 코드뿐 아니라 실패 후 기존 상태가 유지되는지도 확인한다.

## 좋아요 집계

- 좋아요 관계·내 목록 포함 여부는 즉시 반영하며 likeCount·인기순은 Shopping의 저장 집계 값을 사용한다.
- 단일 commerce-api 인스턴스에서 시작 시 1회, 이전 실행 종료 10초 후 전체 COUNT를 재집계한다.
- 집계 행이 없으면 0이며 마지막 취소 후에도 0으로 갱신한다. 전체 갱신 실패는 롤백·로그 후 다음 주기에 실행한다.
- 집계 스케줄러는 application 집계 Service를 호출한다. 집계 쓰기 계약은 읽기 DAO와 분리한다.
- 집계 테스트는 시간을 기다리지 않고 실행을 직접 호출한다. Redis·메시징·다중 인스턴스 조율은 추가하지 않는다.

## 검사 적용

- Checkstyle은 별표·미사용 import 금지부터 적용한다. 기본 계층 의존 검사와 신규 도메인 순수성 검사를 구분한다.
- 신규 순수성 검사는 실제 네 Context와 신규 공통 domain을 포함한다. Example은 참고 코드로 보존한다.
- 현재 존재하는 신규 클래스를 실제로 검사하며 아직 없는 Context의 순수성을 검증했다고 기록하지 않는다.
- 검사 설정·실행 상태는 사실대로 기록한다. 문서에 기준을 적었다고 설정 연결이나 검사 통과로 보고하지 않는다.

## 변경 금지

- 검사를 통과시키기 위해 테스트 기대값, 업무 규칙, Checkstyle 또는 ArchUnit 규칙을 삭제하거나 완화하지 않는다.
- 요청 범위 밖의 패키지 개편과 기존 Example 코드 리팩터링을 함께 진행하지 않는다.
- 사용자가 수정한 파일과 관련 없는 변경을 되돌리거나 덮어쓰지 않는다.
