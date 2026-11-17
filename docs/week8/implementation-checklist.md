# W8 쿠폰 발급·수용 제어 구현 체크리스트

[과제 원문](assignment-original.md) · [학습 자료](learning-original.md) · [9항목 기준](grading-criteria.md) · [전체 설계](../week2/commerce-erd-draft.md) · [API 계약](../week2/commerce-api-contract.md) · [정책 선택](../week2/commerce-policy-decisions.md) · [TDD 계획](../week2/commerce-tdd-plan.md)

## 2026-11-12 고객 발급 설계

멘토 공지는 세마포어 BASIC 샘플을 실험으로 남기고 실제 쿠폰 발급 방식을 선택할 수 있도록 했다. 선택한 [전체 설계](../week2/commerce-erd-draft.md#w8-쿠폰-예약과-발급)·[고객 응답](../week2/commerce-api-contract.md#w8-쿠폰-예약-응답)·[선택 이유](../week2/commerce-policy-decisions.md#w8-쿠폰-발급-선택)·[테스트 기대값](../week2/commerce-tdd-plan.md#w8-쿠폰-예약과-복구-테스트)을 1단계 문서에 반영했다. 2단계에서는 MySQL 예약·발급과 본인 조회를 구현했다. [MySQL TDD 실행 기록](#2단계-mysql-tdd-실행-기록)에 실제 DB 결과를 남겼다. 3단계에서는 신청 POST·Redis 선별·미처리 예약 재개를 연결했다. [3단계 검증](#3단계-redis-선별과-예약-복구)

Redis가 한 명을 선별할 때마다 해당 사용자의 몫과 발급 대기 기록을 MySQL에 함께 커밋한다. API는 이 커밋이 확인된 뒤 “한 장 확보”를 응답한다. 서버 재시작과 응답 유실 뒤에도 커밋된 예약을 서버가 이어서 처리하며 고객은 다시 신청할 필요가 없다. 커밋 전 Redis 선별 기록까지 유실되면 해당 고객의 몫을 복원할 근거가 없다. 100장은 선별 한도의 예시이고 C=3은 동시에 실행할 발급 작업 수의 실험값이다.

4단계에서는 지정한 작업자가 커밋된 예약을 읽어 Redis 전역 세 자리 안에서 발급한다. 두 캠페인이 세 자리를 함께 쓰는 동안 접수된 네 번째 고객이 추가 신청 없이 한 장을 받는 과정을 확인했다. [작업자와 세마포어 검증](#4단계-발급-작업자와-redis-세마포어)

## 2026-11-08 기준 조사 기록

**원문 보존·기준 정리·현재 코드 조사까지 완료했다. W8 기능은 아직 구현하지 않았다.** 현재 브랜치는 `volume-8`, 출발 HEAD는 `325d4e46d619927278edab24603633f9ba58ecf1`이며 문서 변경 전 작업 트리는 깨끗했다. `apps`·`modules` 코드/리소스 조사에서 쿠폰·캠페인 기능과 Redis 수용 Lua는 발견되지 않았다. 수동 DDL은 `012-outbox.sql`까지다.

W7의 [최종 검사 기록](../week7/final-verification.md)은 기존 구현의 근거로 유지한다. 과거 2,190개 통과를 이번 W8 검사로 세지 않는다. 이번에는 앱·Docker·DB·Redis를 실행하거나 데이터·스키마·소스 코드·설정을 변경하지 않았으며 커밋·푸시하지 않았다. 아래는 향후 작업과 증거의 추적표이며 현재 채점 점수를 의미하지 않는다.

## 원문 9항목의 BASIC 작업 상태

| ID | 필요한 작업·증거 | 현재 상태 |
| --- | --- | --- |
| W8-C01 | 실제 발급 201·기존 결과 200, 본인 목록·사용자 격리·안정 정렬 | 선택 설계의 신청 POST 202·기발급 200. 본인 상태·목록 GET, 소유자 격리와 페이지 정렬은 아래 조회 검증에 기록한다. BASIC 동기 HTTP 201은 별도 실험이다. |
| W8-C02 | DB 총량·중복·원자성, 실제 SQL 후 rollback·마지막 수량 경쟁 | 선택 설계의 예약·발급 TX와 실제 MySQL 경합·롤백 검증 완료 |
| W8-C03 | 자리 반환·반환 실패 후에도 쿠폰·issuedCount 보존 | 발급 커밋 시 Redis를 중지해 반환을 실패시켰다. 같은 쿠폰 ID·발급 수를 보존하고 명시적 재개 뒤 토큰 0을 확인했다. |
| W8-C04 | 실제 Redis C=3의 유지 중인 세 실행과 네 번째 429 | 실제 세 자리와 초과 획득 거절 확인. 선택한 고객 POST는 대기 예약을 202로 받음. BASIC 동기 HTTP 429와 구분 |
| W8-C05 | TX·발급 실행 종료 뒤 자기 token 반환·빈자리 재사용 | 실제 발급 커밋·롤백 및 다른 토큰 보존·재사용 확인 |
| W8-C06 | HTTP 실행별 고유 token, 동일 업무 효과 1, 완료 재요청의 자리 획득 생략 | 선택 설계는 발급 작업마다 고유 토큰을 사용한다. 같은 예약 재처리 후 보유 쿠폰 1장, 완료 재요청의 기존 쿠폰 ID를 확인했다. |
| W8-C07 | 실제 Redis 장애·수용 불확실 시 신규 503, 기존 결과 조회 유지 | 전용 Redis 중지 중 신규 POST 503·DB 신규 효과 0, 기발급 POST 200·동일 쿠폰 ID를 확인했다. 기존 본인 GET도 전용 Redis 응답 정지 상태에서 통과했다. |
| W8-C08 | 기본 닫힘·명시적 열기·장애 후 보수적 재개, TTL 자동 회수 금지 | **충족.** 같은 Redis에 이전 작업자의 점유가 남은 경우까지 검증했다. JVM 종료·DB 롤백 확인 → 해당 토큰 1개 정리 → 명시적 기동 뒤 4번의 원래 예약에 쿠폰 1장, 최종 점유 0개였다. [재현 결과](#c08-같은-redis에-이전-작업자의-토큰이-남은-경우) |
| W8-C09 | 관련 실제 테스트·회귀·Checkstyle·ArchUnit·소유 자원 정리 | **충족.** C08 보완 후 양 앱 전체 검사 2,263개 통과, 양쪽 ArchUnit 통과, Checkstyle 위반 0건, 실행 후 테스트 컨테이너 0개. [전체 검사](#c08-보완-후-전체-검사) · [10만 건 부하 실험](#5단계-쿠폰-신청-10만-건과-상품주문-조회-부하) |

## 이번에 추가하고 기존대로 유지할 책임

| 구분 | W8 범위 |
| --- | --- |
| 추가 | 캠페인 로컬 준비, Redis 선별 후 MySQL 예약, 한도·사용자별 유일성 보호, 발급·본인 목록 API |
| 추가 | 발급 대기 예약을 읽는 서버 작업자, Redis 세마포어·작업별 token, 커밋된 예약 재개와 발급 중복 방지 |
| 보존 | 상품·브랜드·좋아요·포인트·기존 주문, PG 결과 복구·소유권·과거 POINT 기록·기존 TX/락 |
| 보존 | W7 주문 확정·Outbox 공동 TX, 단일 relay·불변 사건·구매 활동 소비·중복 방지·실패 정책 |
| 범위 밖 | 쿠폰의 주문 할인 적용·사용·환불·알림, 화면을 열어 둬야 하는 대기실, 전체 캠페인 CRUD, 쿠폰 이벤트/Outbox의 임의 연결 |

`docs/week1/order-discount-contract.md`는 과거 주문 할인 설계다. W8의 쿠폰 **발급** 구현이나 합의된 사용 정책으로 이어받지 않는다. 이번 설계는 쿠폰 예약·발급·조회에만 적용한다.

## 현재 코드와 영향 후보

아래 표는 기준 조사 당시의 영향 후보이며, 구현된 쿠폰 클래스·테이블과 검증 결과는 2단계 실행 기록에 연결했다.

| 책임 | 현재 근거 | W8에서 확인·변경할 내용 |
| --- | --- | --- |
| 고객 식별·공통 HTTP | [LikeController](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/like/LikeController.java), [UserResolver](../../apps/commerce-api/src/main/java/com/loopers/application/user/UserResolver.java), [StrictInput](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/commerce/StrictInput.java), [ApiResponse](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/ApiResponse.java) | X-USER-ID와 기존 식별·입력/응답 계약을 연결. 임의 요청 userId로 대상 사용자를 바꾸지 않음 |
| 공통 오류 경로 | [CommerceWebConfiguration](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/commerce/CommerceWebConfiguration.java), [CommerceErrors](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/commerce/CommerceErrors.java) | 현재 commerce URL 분류에 coupons/coupon-campaigns가 없음. 신규 경로도 공통 오류 계약을 적용하도록 검토. 429·503과 업무 오류를 구분 |
| 사용자 fixture | [FixtureUserIdentityRepository](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/user/FixtureUserIdentityRepository.java), [FixtureUserInitializer](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/user/FixtureUserInitializer.java) | 현재 alice·bob·admin 세 식별값. C=3의 네 사용자 실험 등에는 테스트 전용 식별/DB 준비 필요. 임의 숫자 헤더가 사용자를 만드는 것으로 가정하지 않음 |
| DB TX·잠금·중복 | [LikeService](../../apps/commerce-api/src/main/java/com/loopers/application/like/LikeService.java), [UserJpaRepository](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/user/UserJpaRepository.java), [004-like.sql](../../apps/commerce-api/src/main/resources/db/schema/004-like.sql) | 기존 TX·잠금·DB 유일 제약의 형태를 참고. 캠페인·쿠폰의 저장 모델, 잠금/조건부 변경·새 DDL은 별도 설계 |
| Redis master | [RedisConfig](../../modules/redis/src/main/java/com/loopers/config/redis/RedisConfig.java), [RedisProperties](../../modules/redis/src/main/java/com/loopers/config/redis/RedisProperties.java), [redis.yml](../../modules/redis/src/main/resources/redis.yml) | qualifier redisTemplateMaster, 실제 타입 RedisTemplate<String, String>, MASTER·String serializer 확인. 기본 bean은 REPLICA_PREFERRED. 공통 설정에 명시 command timeout 속성이 없어 구체화 필요 |
| 기존 Redis 캐시 | [ProductListCacheConfiguration](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/product/cache/ProductListCacheConfiguration.java) | 별도 StringRedisTemplate·standalone 연결·자체 timeout이다. 해당 값을 공통 master 설정으로 오인하거나 캐시 fallback을 발급에 복사하지 않음 |
| 신규 수용 기능 | 위 master 연결과 향후 apps/commerce-api/src/main/resources/redis/ script | 공유 키·고유 token·원자 수용·소유 반환·닫힘/재개 책임을 추가. 구체 패키지/설정·로컬 제어 방식은 설계에서 선택 |
| 기존 W7 경로 | [DomainEventListener](../../apps/commerce-api/src/main/java/com/loopers/application/event/DomainEventListener.java), [TransactionalOutboxRecorder](../../apps/commerce-api/src/main/java/com/loopers/application/event/TransactionalOutboxRecorder.java), [OutboxRelay](../../apps/commerce-api/src/main/java/com/loopers/application/outbox/OutboxRelay.java) | 유지할 기존 동작이다. 이번 쿠폰 발급을 주문 확정 이벤트·relay에 연결하지 않음 |
| 계층 의존 | [ArchitectureTest](../../apps/commerce-api/src/test/java/com/loopers/architecture/ArchitectureTest.java) | domain→상위 계층, application→interfaces/infrastructure, interfaces→infrastructure 금지 유지. 수용 경계의 포트·adapter 위치를 규칙 안에서 설계 |

## 실제 검증 기반의 출발점

| 근거 | 재사용·주의 |
| --- | --- |
| [LikeServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/like/LikeServiceIntegrationTest.java), [LikeApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/like/LikeApiE2ETest.java) | 실제 SQL 후 rollback·HTTP 동시 중복 검증 형태 참고. 기존 기대값을 쿠폰 통과로 간주하지 않음 |
| [OutboxSchemaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/outbox/OutboxSchemaIntegrationTest.java) | 격리 MySQL에 수동 DDL 적용·JPA validate·DB 제약을 확인하는 형태 참고 |
| [MySqlTestContainersConfig](../../modules/jpa/src/testFixtures/java/com/loopers/testcontainers/MySqlTestContainersConfig.java), [RedisTestContainersConfig](../../modules/redis/src/testFixtures/java/com/loopers/testcontainers/RedisTestContainersConfig.java) | 소유한 DB·Redis fixture. 기존 Redis fixture는 같은 endpoint를 master/replica로 사용하므로 복제 지연·failover 실험 증거는 아님 |
| [OwnedRedisContainer](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/product/cache/OwnedRedisContainer.java), [RedisProductListCacheIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/product/cache/RedisProductListCacheIntegrationTest.java) | 자신이 만든 고정 endpoint의 실제 중단·재시작 방식 참고. helper는 현재 cache 패키지 전용이며 공유화가 필요하면 영향부터 검토 |
| [RedisCleanUp](../../modules/redis/src/testFixtures/java/com/loopers/utils/RedisCleanUp.java), [DatabaseCleanUp](../../modules/jpa/src/testFixtures/java/com/loopers/utils/DatabaseCleanUp.java) | FLUSHALL·전체 테이블 truncate가 포함된다. 공유 Redis·사용자 DB에 호출하지 않으며 자기 격리 환경인지 먼저 확인 |
| [W7 최종 검사](../week7/final-verification.md) | 기존 테스트 context cache 상한·연결 풀 수명 보완을 유지. 이번 작업의 실제 새 실행은 별도 기록 |

## 11월 8일 BASIC 기준 적용 당시의 요구와 질문

BASIC 원문은 최초 201·기존 200, 자리 없음 429·수용 상태 확인 불가 503을 검증한다. C=3은 이 수용 실험의 값이다. 선택 설계에서는 발급 작업자 3명이 모두 자리를 점유해도 잔여 몫이 있는 네 번째 고객의 예약을 받을 수 있다.

아래 표는 11월 8일 원문 조사에서 남긴 질문이다. 제품 경로에서 적용할 선택과 아직 확인할 정책은 [현재 정책 문서](../week2/commerce-policy-decisions.md#w8-쿠폰-발급-선택)를 따른다.

| ID | 정할 내용 | 영향 |
| --- | --- | --- |
| W8-Q01 | 발급·목록 JSON, 구체적인 안정 정렬·페이지 유무, 고정 혜택 표현과 캠페인 fixture 준비 방식 | 고객 응답·저장 사실·로컬 재현 입력 |
| W8-Q02 | 품절·비활성 등 업무 오류의 HTTP 상태·코드·메시지, 429·503의 구체 오류 표현·재시도 안내 | 기존 공통 API 오류와 구분. Retry-After 등 안내가 발급 예약을 의미하지 않음 |
| W8-Q03 | 캠페인 경쟁을 보호할 잠금/조건부 변경 방식, 저장 구조·DDL·TX 내 중복 재검사와 충돌 복구 경계 | 실제 총량·중복·rollback 및 기존 DB 접근과의 영향 |
| W8-Q04 | 실행 설정의 C·전용 공유 키·command timeout, 명시적 열기/닫기·종료 확인·자기 비활성 token 복구를 제공할 로컬 방법 | 기본 닫힘·fail-closed 요구를 구현하는 구체 방식. 라이브러리 기본값·PONG을 안전성 근거로 삼지 않음 |
| W8-Q05 | DB commit 뒤 자기 token 반환이 실패한 요청의 HTTP 응답·관찰 방식 | 쿠폰·issuedCount 보존과 신규 수용 닫힘은 확정. 저장된 성공을 어떤 응답으로 안내할지는 별도 선택 |

## 고객 발급 5단계와 완료 조건

| 단계 | 작업 | 완료 기준·상태 |
| --- | --- | --- |
| 1. 고객 약속과 복구 기준 | 예약·발급 커밋, 고객 응답, 재신청 없는 복구의 설계와 테스트 기대값 | 설계와 테스트 기대값 작성 완료 |
| 2. MySQL 예약·발급·본인 목록 | 한도와 사용자별 유일성, 예약에서 보유 쿠폰으로 전환 | 완료. 실제 MySQL에서 마지막 한 장 경합·중복 예약/발급·롤백·본인 조회 검증 |
| 3. Redis 선별·신청 API | 선별된 사용자만 DB 예약. 동일 신청 재확인과 선별 후 중단 복구 | 완료. 100명 예약 뒤 101번째 신규 요청 DB 연결 0, 물리적 커밋 이후 202, 롤백·커밋 응답 유실 및 미처리 예약 자동 재개 검증 |
| 4. 작업자와 세마포어 | 서버가 커밋된 대기 예약을 읽어 빈자리에서 발급 | 완료. 두 캠페인이 C=3을 공유한 상태에서 4번과 5번을 예약하고, 한 자리가 반환되자 4번이 먼저 자동 발급에 진입했다. |
| 5. 통합 장애·부하·회귀 | 실제 격리 DB·Redis에서 예약·발급 커밋 후 중단, 응답 유실과 재처리, 상품·주문 동시 요청 | 장애·복구, 10만 신청의 DB 접근 및 상품·주문 상세 조회 영향, C08 보완 후 전체 회귀를 확인했다. 예약형 기준 9개 항목에 구현·검증 근거가 있다. PR·기술 글·실제 제출은 별도로 확인한다. |

## 2단계 MySQL TDD 실행 기록

[예약·발급 서비스](../../apps/commerce-api/src/main/java/com/loopers/application/coupon/CouponIssuanceService.java) · [MySQL 제약과 테이블](../../apps/commerce-api/src/main/resources/db/schema/013-coupon-issuance.sql) · [통합 테스트](../../apps/commerce-api/src/test/java/com/loopers/application/coupon/CouponIssuanceIntegrationTest.java)

가장 먼저 한도 1인 캠페인에 서로 다른 두 사용자가 신청하는 테스트를 작성했다. 서로 다른 MySQL 연결에서 트랜잭션을 시작하고 두 요청을 같은 신호로 풀었다. 한도 검사를 연결하기 전에는 두 요청이 예약돼 예상한 품절 1건이 실제 0건이었다. 캠페인 행을 잠근 상태에서 기존 예약·잔여 수량을 확인하고 대기 수 증가와 예약 저장을 같은 트랜잭션에 넣자, 예약 1명·품절 1명으로 통과했다. 어느 사용자가 먼저 잠금을 얻는지는 기대값에 넣지 않았다.

| 검증한 상황 | 커밋·롤백 후 별도 조회한 결과 |
| --- | --- |
| 서로 다른 두 사용자가 마지막 한 장에 동시 신청 | `WAITING` 1건, 품절 1건, 발급 완료 0건, 보유 쿠폰 0장 |
| 같은 사용자가 마지막 한 장에 동시 신청 | 두 응답의 예약 ID가 같고 대기 수는 1 |
| 확보 후 캠페인이 닫히고 같은 예약을 두 실행이 동시 발급 | 두 결과의 쿠폰 ID가 같고 대기 0, 발급 완료 1, 보유 쿠폰 1장 |
| 예약·발급 변경 SQL을 실행한 뒤 같은 트랜잭션에서 실패 | 예약 롤백 뒤에는 다른 사용자가 마지막 한 장을 확보한다. 발급 롤백 뒤에는 기존 사용자의 예약이 남아 같은 예약으로 재처리한다. |
| 보유 쿠폰 INSERT 자체를 MySQL 트리거에서 실패시킴 | 호출자에게 SQL 오류가 전달된다. 원래 예약 ID로 대기 1·보유 0을 재조회하고, 오류 원인을 제거한 뒤 같은 예약으로 한 장을 발급한다. |
| 서비스를 거치지 않고 SQL로 중복 예약·중복 발급·한도 초과를 시도 | 두 중복 INSERT는 MySQL 오류 1062, 한도 초과 UPDATE는 3819로 거절됐다. 기존 예약 ID·쿠폰 ID·발급 수는 보존됐다. |
| 본인 조회 | 대기 중에는 캠페인 상태만 보이며 내 쿠폰 목록은 비어 있다. 완료되면 본인 목록에만 나타난다. 발급 시각과 쿠폰 ID 내림차순으로 정렬한다. |

예약과 발급은 각각 같은 캠페인 행에 비관적 쓰기 잠금을 획득한다. 사용자·캠페인별 예약과 예약별 보유 쿠폰에 각각 DB 유일 제약이 있다. 수동 DDL 013은 `대기 수 + 발급 수 ≤ 총 한도`를 CHECK로도 검사한다. 전용 MySQL 8 컨테이너에 기존 사용자 DDL과 013을 적용하고 매핑을 검증했다. HTTP 확보 응답, Redis 선별, 작업자의 자동 재개는 3~5단계에서 검증한다.

최종 관련 검사에서 쿠폰 통합 10개, 기존 커머스 스키마 33개, Outbox 스키마 5개, ArchUnit 1개로 총 49개가 통과했다. 실패·오류·건너뜀과 API main/test Checkstyle 위반은 모두 0건이었다. 실행 종료 후 Testcontainers 컨테이너가 남아 있지 않은 것을 확인했다.

```sh
./gradlew --offline :apps:commerce-api:test \
  --tests com.loopers.application.coupon.CouponIssuanceIntegrationTest \
  --tests com.loopers.infrastructure.schema.CommerceSchemaIntegrationTest \
  --tests com.loopers.infrastructure.outbox.OutboxSchemaIntegrationTest \
  --tests com.loopers.architecture.ArchitectureTest \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

## 3단계 Redis 선별과 예약 복구

신청에서 Redis가 확인하는 값은 **이 캠페인에서 누구의 몫을 잡았는지**다. MySQL은 **실제로 누구에게 한 장을 약속했는지**를 기록한다. Redis에는 쿠폰 ID를 미리 만들지 않는다. 발급 작업자가 쓰는 동시 실행 세마포어는 4단계에서 연결한다.

| 순서 | 저장과 판단 |
| --- | --- |
| 캠페인 열기 | 기본은 닫힘이다. 명시한 캠페인만 MySQL의 한도·활성 상태·기존 예약자를 읽고 Redis의 기존 배정과 대조한 뒤 연다. 이때만 전체 예약자를 조회한다. |
| 신규 신청 | master Lua가 기존 신청자부터 확인한다. 남은 몫이 있으면 사용자와 미처리 Stream 항목을 함께 기록한다. 정상 마감 상태의 미배정 사용자는 여기서 응답한다. |
| 예약 | 선별에 성공한 요청은 2단계의 MySQL 예약 트랜잭션을 호출한다. 커밋 결과가 불확실하면 새 트랜잭션으로 같은 캠페인·사용자를 조회한다. 예약 확인 후 해당 사용자의 Stream 항목만 완료 처리하고 사용자 배정은 보존한다. |
| 서버 중단 | 선별 직후 서버가 멈췄다면 명시적으로 연 캠페인의 미처리 항목에서 같은 사용자로 예약을 재개한다. 이전 커밋이 이미 성공했다면 기존 예약을 읽는다. MySQL 커밋 전에 Redis의 유일한 기록까지 유실되면 사용자 식별과 예약 복구가 불가능하다. |

**실행 설정.** `commerce.coupon.admission.enabled`의 기본값은 `false`다. 운영자가 대상 캠페인을 DB에 준비하고 이전 실행이 종료됐음을 확인한 뒤 `enabled=true`와 `commerce.coupon.admission.campaign-ids`(예: `42,43`)를 함께 지정해 시작한다. 선택 기록이 남은 경우 DB와 대조해 이어간다. 복구 작업의 기본 간격은 5초이고 캠페인당 한 회에 최대 100건의 대기 기록을 읽는다. 이 수치는 예약 복구용 초기값이다. 대상 캠페인의 수용 상태가 유실되거나 일치하지 않으면 로컬 수용을 닫으며 주기 작업은 닫힌 캠페인을 다시 열지 않는다. 재개는 기존 실행 종료와 DB·Redis 상태를 확인한 뒤 명시적으로 수행한다. 설정을 켠 채 앱을 재시작하면 지정한 캠페인을 다시 대조하여 연다. 실행을 기다리는 잠금은 해당 JVM에 적용되므로 여러 인스턴스에서 장애 복구 시에는 다른 인스턴스의 작업 종료도 확인해야 한다.

먼저 작성한 두 HTTP 테스트는 경로 연결 전 첫 신청 404와 JDBC 커밋 미도달로 실패했다. 연결한 뒤에는 전용 MySQL 8·Redis 7.4, 실제 HTTP 서버에서 다음 결과를 확인했다. 테스트 클라이언트의 자동 재전송을 끄고 첫 응답을 관찰했으며, DB 관찰에 사용한 별도 연결은 요청의 연결 수에서 제외했다.

| 실제 실험 | 관찰한 결과 |
| --- | --- |
| 서로 다른 사용자 100명이 차례로 신청한 뒤 신규 101번째와 기존 100번째가 각각 신청 | 첫 100명은 202, MySQL 대기 예약 100건. 101번째는 409 마감이며 앱의 DB 연결 증가 0. 기존 사용자는 같은 예약 ID를 받고 수량은 그대로였다. |
| 실제 예약 SQL 실행 후 JDBC 커밋 직전에 요청을 멈춤 | 별도 DB 연결에서는 예약 0건이었다. 클라이언트는 대기했고, 커밋을 허용한 뒤 202와 MySQL에 저장한 예약 ID를 받았다. |
| 커밋 직전 강제 롤백 / 커밋 성공 직후 확인만 유실 | 롤백은 503·예약 0건. Redis 미처리 기록으로 고객 재신청 없이 예약을 복구했다. 커밋 확인만 유실된 경우는 새 연결에서 기존 예약을 확인해 202를 반환했다. |
| Redis 배정 직후 MySQL에 접근하기 전 중단 | 새 애플리케이션 빈을 구성해 같은 Redis와 MySQL에 연결하고 기존 선별 기록으로 예약 한 건을 복구했다. 별도 실험에서 실제 스케줄러가 고객 POST 없이 예약한 결과도 확인했다. |
| 발급 완료 후 같은 POST, 비활성·미개방, 잘못된 요청 | 기존 쿠폰 ID로 200. 비활성 신규는 409, 미개방은 503. 다른 사용자 ID를 넣은 본문이나 유효하지 않은 요청은 거절했다. |

[실제 HTTP·커밋 통합 테스트](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponAdmissionHttpIntegrationTest.java) · [Redis 선별 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/coupon/RedisCouponSelectionStoreIntegrationTest.java) · [신청과 커밋 확인](../../apps/commerce-api/src/main/java/com/loopers/application/coupon/CouponAdmissionService.java) · [master 선별 스크립트](../../apps/commerce-api/src/main/resources/redis/coupon-selection.lua) · [예약 복구 실행 설정](../../apps/commerce-api/src/main/java/com/loopers/config/coupon/CouponReservationRecoveryConfiguration.java)

최종 검사에서 쿠폰 관련 35개, 기존 결제 오류 계약 25개, ArchUnit 1개로 총 61개가 통과했다. 실패·오류·건너뜀과 API main/test Checkstyle 위반은 모두 0건이었다. 격리 컨테이너 종료도 확인했다. 이 실행에서 100명 신청 완료 후 마감을 검증했으며, 10만 동시 신청 부하와 타 API 지연은 5단계에서 측정한다.

```sh
./gradlew --offline :apps:commerce-api:test \
  --tests 'com.loopers.interfaces.api.coupon.*' \
  --tests 'com.loopers.infrastructure.coupon.*' \
  --tests 'com.loopers.application.coupon.*' \
  --tests com.loopers.interfaces.api.payment.PaymentErrorContractTest \
  --tests com.loopers.architecture.ArchitectureTest \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

## 4단계 발급 작업자와 Redis 세마포어

한 고객이 한 번 보낸 신청은 3단계에서 MySQL 예약으로 확정된다. 발급 작업자는 지정한 캠페인의 커밋된 `WAITING`을 예약 ID 오름차순으로 읽는다. 실행별 UUID로 Redis master의 전역 Set에 자리를 확보한 뒤 2단계의 `issue()`를 호출한다. 발급 트랜잭션이 끝나면 해당 실행의 토큰만 반환한다. 이미 발급한 예약에는 기존 쿠폰이 남는다.

| 실제로 멈춘 지점 | 격리 MySQL·Redis와 HTTP에서 확인한 결과 |
| --- | --- |
| 두 캠페인의 작업 3개가 자리 획득 직후, T2 진입 전 대기 | 전역 Set에 서로 다른 토큰 3개. 4번과 5번의 POST는 각각 `202 WAITING`을 반환했다. 대기 예약 5건, 보유 쿠폰 0장이었다. |
| 1번의 쿠폰 SQL 실행 후 물리 커밋 직전 | 세 토큰 모두 유지됐고 별도 DB 연결에서는 여전히 보유 쿠폰이 0장이었다. 4번과 5번은 발급에 진입하지 않았다. |
| 커밋 및 한 자리 반환 | 1번 토큰만 교체되고 2·3번 토큰은 유지됐다. 4번이 먼저 진입했고 5번은 대기했다. 4번은 추가 POST 없이 원래 예약의 `ISSUED`와 쿠폰 1장을 얻었다. 마지막에는 5명에 쿠폰 5장, 대기 0, 토큰 0이었다. |
| T2 커밋을 실제로 롤백 | 같은 예약 ID로 대기 1건·보유 0장이 남았다. 앞선 토큰을 반환하고 서버가 다음 회차에 새 토큰으로 발급해 최종 쿠폰은 1장이었다. |

구현 전 첫 HTTP 테스트는 예약 POST까지 성공하고도 발급 진입자가 0명이어서 실패했다. 작업자를 연결한 뒤 테스트 장벽을 Redis 획득 직후와 실제 JDBC 커밋 직전에 두어 반환 순서까지 관찰했다. [작업자 통합 테스트](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponIssuanceWorkerHttpIntegrationTest.java) · [작업자](../../apps/commerce-api/src/main/java/com/loopers/application/coupon/CouponIssuanceWorker.java) · [세마포어 Lua](../../apps/commerce-api/src/main/resources/redis/coupon-issue-semaphore.lua)

**실행 설정.** 발급할 캠페인을 준비한 후 지정한 한 API 인스턴스에 `commerce.coupon.worker.enabled=true`와 `commerce.coupon.worker.campaign-ids`(예: `42,43`)를 함께 설정한다. 기본은 꺼짐이고, 초기값은 전역 자리 `capacity=3`, 조회 간격 `poll-delay=1s`다. 캠페인별 접수 설정은 3단계 설정을 사용한다. 제공한 수동 스키마의 014는 기존 예약 데이터에 대기 예약용 인덱스를 추가한다. [스키마 적용 안내](../week2/commerce-schema-operations.md#w8-쿠폰-예약발급-스키마)

**실패와 재개.** 발급 TX가 예외를 내면 기존 캠페인 잠금을 다시 얻어 같은 예약의 커밋·롤백 결과를 확인한다. 확인할 수 없는 동안에는 자리를 보존하고 신규 발급을 닫는다. Redis 응답 유실도 자동 열기나 TTL에 의한 회수를 하지 않는다. 같은 JVM에서는 실행이 모두 종료되고 예약 결과를 대조한 뒤 `worker.open()`으로 자신의 종료된 토큰만 정리할 수 있다. 프로세스 종료 후 새 JVM에 소유 근거가 없는 토큰이 남아 있으면 닫힘을 유지한다. 이 경우 이전 작업자의 종료와 로그의 토큰·예약 ID 및 DB 결과를 운영자가 대조해야 한다. 전역 Set 전체를 비우는 방식은 사용하지 않는다.

다른 JVM이 명시적으로 이어받는 순서와 실행 명령은 [잔류 토큰 복구 절차](coupon-worker-token-recovery.md)에 정리했다.

Redis에 획득 결과를 묻는 도중 응답이 유실된 경우도 확인했다. 두 실행의 토큰 UUID를 의도적으로 같게 만든 테스트에서, 후발 작업자가 재개하면서 선행 작업자의 토큰을 지울 수 있었다. 획득 시 토큰과 작업자 식별값을 함께 저장하고 반환·재개 시 둘을 확인하도록 수정했다. 같은 입력으로 다시 실행해 선행 작업자의 토큰이 보존되고 후발 작업자는 닫힌 채로 남는 것을 확인했다. [응답 유실·소유자 경합 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/coupon/RedisCouponIssueSemaphoreIntegrationTest.java)

3단계 마지막에 기재한 Gradle 명령으로 다시 검사했다. 신규 17개(작업자 단위 7, Redis 세마포어 8, 발급 HTTP 통합 2)를 포함해 쿠폰 52개, 기존 결제 오류 계약 25개, ArchUnit 1개로 총 78개가 통과했다. 실패·오류·건너뜀 및 API main/test Checkstyle 위반은 0건이었다. 전용 Testcontainers가 종료됐음을 확인했다. 전체 앱 회귀·대규모 부하·실제 Redis 중단 후 재개는 이 실행의 범위 밖이다.

## 본인 예약과 보유 쿠폰 조회

신청 후 화면을 다시 열면 고객은 본인 상태 GET과 목록 GET만 호출한다. 발급 직전과 직후에 화면이 어떻게 바뀌는지 확인하기 위해 실제 신청과 작업자 발급을 연결했다. 처음 실행에서는 MySQL에 예약이 커밋돼도 상태 GET이 404였다. 두 GET을 MySQL 읽기 경로에 연결한 뒤 같은 시나리오를 다시 실행했다. [신청부터 조회까지의 통합 테스트](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponQueryHttpIntegrationTest.java) · [HTTP 경계·Redis 장애 시 조회 테스트](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponQueryBoundaryHttpIntegrationTest.java)

| 상황 | 실제 HTTP·MySQL에서 확인한 결과 |
| --- | --- |
| 방문한 캠페인에 다른 고객만 신청·발급 완료 | 내 상태는 `200 NOT_APPLIED`이며 예약·쿠폰 ID와 내 예약 행이 없다. 없는 캠페인은 `404 COUPON_CAMPAIGN_NOT_FOUND`다. |
| 신청 1회 후 작업자의 JDBC 발급 커밋 직전 | 내 상태는 `200 WAITING`, 원래 예약 ID가 보이고 내 쿠폰은 0장이다. 같은 캠페인의 다른 고객은 이미 발급받은 자기 쿠폰만 본다. |
| 커밋 뒤 같은 GET으로 다시 조회 | 상태는 `200 ISSUED`이고 예약 ID는 같다. 상태의 쿠폰 ID와 내 목록의 쿠폰 ID가 일치한다. |
| 대기 예약·다른 사람 쿠폰이 있는 상태에서 목록 페이지 조회 | 내 발급 쿠폰만 전체 건수에 포함한다. 발급 시각이 같으면 큰 쿠폰 ID가 앞서고 범위 밖 페이지는 빈 배열과 본인 전체 건수를 반환한다. |
| 격리 Redis가 응답하지 않고 MySQL은 정상 | 대기 고객의 상태와 빈 목록, 발급 고객의 상태와 보유 쿠폰이 각각 조회된다. 이 검사는 신규 신청 차단·작업자 재개와 별개다. |

GET 두 경로에 `Cache-Control: no-store`를 적용해 브라우저나 공유 캐시가 이전 사용자의 상태를 재사용하지 않도록 했다. 잘못된 입력·헤더, 사용자별 결과, 발급 시각 동률, 범위 밖 페이지 및 캐시 헤더를 HTTP로 확인했다. 3단계 기록의 Gradle 명령으로 조회 테스트 14개를 포함해 관련 테스트 92개가 통과했다. 실패·오류·건너뜀과 API main/test Checkstyle 위반은 모두 0이었다. ArchUnit 통과와 테스트 전용 컨테이너 종료를 확인했다.

## 5단계 Redis 장애와 명시적 재개

전용 Redis 프로세스를 중지하고 같은 컨테이너·포트에서 재시작했다. MySQL과 API는 계속 실행했다. 발급 자리 1개에 2번 고객의 발급 트랜잭션을 멈추고, 다른 캠페인의 4번 고객이 POST 한 번으로 `202 WAITING`을 받게 했다. Redis에 점유 토큰을 저장한 뒤 장애를 일으켜, 실행 중이던 작업의 자리 반환과 대기 예약의 재개를 한 흐름에서 관찰했다. [장애·재개 통합 테스트](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponRedisOutageRecoveryHttpIntegrationTest.java)

| 시점 | 실제 응답과 저장 결과 |
| --- | --- |
| Redis 프로세스 중지·PING 실패 | 신규 10번은 `503 COUPON_ADMISSION_UNAVAILABLE`을 받고 예약 0건이었다. 기발급 1번의 같은 POST는 `200 ISSUED`와 원래 쿠폰 ID를 반환했다. 2번의 발급은 커밋됐고 토큰 반환은 실패했다. 4번의 예약은 계속 `WAITING`이었다. |
| 같은 Redis가 다시 PONG 응답 | 중지 전 남긴 토큰 하나가 그대로였다. 장애를 감지한 캠페인 접수와 작업자는 닫힌 상태였다. 신규 11번은 여전히 503을 받았고 4번의 쿠폰은 0장이었다. |
| 접수와 작업자를 명시적으로 재개 | 접수 서비스가 캠페인의 DB 예약자와 Redis 선별 기록을 대조했다. 작업자는 종료된 자기 실행의 토큰을 정리하고 4번의 기존 예약을 발급했다. 4번은 추가 POST 없이 같은 예약 ID로 쿠폰 1장, 토큰은 최종 0개였다. 복구를 반복해도 1·2·4번의 쿠폰 ID와 수량은 유지됐다. 10·11번의 예약은 0건이었다. |
| 재개 이후 처음 신청한 12번 | `202 WAITING`을 받고 서버가 한 장을 발급했다. 같은 캠페인의 완료 수는 2에서 3이 됐으며 4번의 쿠폰 ID는 그대로였다. |

**수정한 두 문제.** 첫 실행에서 Redis 장애 중 기발급 고객의 POST도 503을 반환했다. 선별을 확인할 수 없을 때는 해당 고객·캠페인의 커밋 기록만 읽도록 고쳤다. 이때 신규 요청도 읽기 조회 한 번이 필요할 수 있다. 신규 예약·발급 쓰기는 시작하지 않는다. 정상 상태에서 마감된 101번째 신청의 쿠폰 DB 접근은 기존처럼 0회다.

별도 사례로 Redis 연결은 유지하면서 응답을 정지했다. 수정 전에는 기존 HTTP 클라이언트의 20초 읽기 제한이 먼저 끝났다. 쿠폰 선별·세마포어가 쓰는 master 연결에 명령·연결 대기 `datasource.redis.master-timeout` 기본 2초를 두고, 이미 끊긴 연결에는 새 명령을 적재하지 않도록 했다. 같은 테스트에서 HTTP 503과 DB 신규 효과 0을 확인했다. 2초는 Redis 대기 설정이며 요청 전체의 응답 시간 약속은 아니다. 이미 전송된 Lua의 응답만 잃은 경우에는 기존 미처리 선별 기록을 재개 시 대조한다. [읽기 전용 결과 확인](../../apps/commerce-api/src/main/java/com/loopers/application/coupon/CouponAdmissionService.java) · [master 연결 설정](../../modules/redis/src/main/java/com/loopers/config/redis/RedisConfig.java)

이 실험은 Redis 저장 내용을 보존한 동일 API 인스턴스의 재개를 다룬다. API 프로세스와 Redis 기록을 함께 잃는 경우는 [별도 복구 실험](#5단계-앱-종료와-redis-데이터-유실)에서 다룬다.

관련 회귀 104개가 통과했다. 쿠폰 70개, 기존 결제 오류 계약 25개, 상품 목록 캐시 8개, API ArchUnit 1개이며 실패·오류·건너뜀은 모두 0이었다. API main/test와 Redis 모듈 main Checkstyle 위반은 0건이었다. 실행 종료 후 테스트 컨테이너도 남아 있지 않았다. 장애 두 사례는 아래 명령으로 따로 재현할 수 있다.

```sh
./gradlew --offline :apps:commerce-api:test \
  --tests com.loopers.interfaces.api.coupon.CouponRedisOutageRecoveryHttpIntegrationTest --console=plain
```

## 5단계 앱 종료와 Redis 데이터 유실

재현에서는 쿠폰 HTTP API·접수·발급 스케줄러를 별도 JVM으로 실행한다. 테스트 전용 MySQL에 캠페인 한 장과 4번 고객을 처음 한 번만 준비한다. 이후 모든 JVM은 이 스키마를 `validate`로 연다. 이전 JVM과 Redis 컨테이너의 수명은 별도로 관리한다. [재현 테스트](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponProcessLossRecoveryHttpIntegrationTest.java) · [테스트 JVM 진입점](../../apps/commerce-api/src/test/java/w8/recovery/CouponRecoveryApplication.java)

기존 앱에서 신청은 POST 한 번이다. 작업자가 Redis 토큰을 얻고 `issue()`의 발급 트랜잭션으로 들어가기 직전에 멈춘다. 독립 DB 연결로 `WAITING` 예약을 확인하고 그 JVM을 강제 종료한다. 이어서 이 테스트의 Redis 컨테이너를 폐기한다. 새 Redis에는 이전 컨테이너의 스냅샷·볼륨을 전달하지 않는다. 새 API를 기동하기 전에 키 개수가 0인지, MySQL에 원래 예약이 남아 있는지를 확인한다.

| 관찰 시점 | 확인한 값 |
| --- | --- |
| 첫 JVM에서 4번에게 확보 응답 | `202 WAITING`, 예약 ID 반환. 캠페인 대기 1·발급 0, 보유 쿠폰 0장. 중단 직전 Redis 발급 토큰은 1개였다. |
| 기존 JVM 종료 확인 후 Redis 교체 | 새 컨테이너에서 `DBSIZE = 0`. 같은 MySQL에서 4번의 원래 예약 ID가 여전히 `WAITING`이었다. |
| 발급을 멈춘 대조 실행 (Red) | 새 작업자의 발급도 차단하자 기대한 `ISSUED` 검사가 실패했다. 실제 DB에는 대기 1·발급 0·보유 0장이 남았다. |
| 새 작업자 재개 (Green) | 지정된 한 작업자를 켜자 `findWaiting`이 원래 예약 ID를 반환했다. 추가 POST 없이 같은 예약은 `ISSUED`, 대기 0·발급 1·보유 1장이 됐다. 본인 상태와 목록 GET의 쿠폰 ID도 같았다. |
| 세 번째 JVM 재기동 | 작업자가 DB를 다시 조회한 뒤에도 같은 예약 1건·같은 쿠폰 ID 1장, 최종 Redis 발급 토큰 0개였다. |

이 실험의 복구 기준은 MySQL **예약 커밋**이다. 새 작업자는 DB에서 처리할 고객을 찾고, 새 Redis에서 발급 자리를 얻는다. DB 커밋 전에 Redis에만 있던 신청은 이 실험의 보장 범위 밖이다. 이번 검증에는 테스트 코드와 문서만 추가·수정했다.

```sh
./gradlew --offline :apps:commerce-api:test \
  --tests com.loopers.interfaces.api.coupon.CouponProcessLossRecoveryHttpIntegrationTest --console=plain
```

관련 회귀 결과는 13개 스위트, 쿠폰 테스트 71개와 API ArchUnit 1개 통과였다. 실패·오류·건너뜀은 모두 0건이고 API main/test Checkstyle 보고서의 위반도 0건이었다. 실행 종료 뒤 Testcontainers 컨테이너는 0개였다.

## C08 같은 Redis에 이전 작업자의 토큰이 남은 경우

2026-11-13. 전용 MySQL·Redis를 처음부터 끝까지 유지했다. 4번 고객은 한 번만 신청해 `202 WAITING`과 예약 ID를 받았다. 첫 작업자가 해당 예약의 보유 쿠폰 INSERT를 실제로 실행한 뒤, 커밋 직전에서 멈췄다. [프로세스·HTTP 통합 테스트](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponProcessLossRecoveryHttpIntegrationTest.java) · [Redis 소유권 대조 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/coupon/RedisCouponIssueSemaphoreIntegrationTest.java)

| 시점 | 관찰 |
| --- | --- |
| 원래 작업자의 발급 TX 진행 중 | 별도 연결에서 캠페인 행의 `FOR UPDATE NOWAIT`가 MySQL 3572로 실패했다. 커밋한 예약은 대기 1건, 외부에서 보이는 보유 쿠폰은 0장이었다. |
| 이전 JVM 강제 종료 | PID 종료를 확인했다. 별도 TX에서 같은 캠페인 행 잠금을 얻었고, 정확한 캠페인·사용자·예약의 `WAITING` 및 보유 0장을 읽고 커밋했다. 다른 사용자로 확인하면 결과 0행이었다. |
| 같은 Redis로 새 JVM 기동 | 이전 작업자의 토큰 1개와 소유자 필드가 남아 있어 발급이 닫혔다. 본인 GET은 같은 예약 ID와 `WAITING`을 반환했다. |
| 검증한 토큰 1개 정리 | 원래 JVM 로그의 세대·소유자·획득 토큰·캠페인·예약·사용자를 대조했다. 명령 결과는 1, 점유 Set과 `held`는 0, 해당 소유자 필드는 없어졌고 세대는 그대로였다. 실행 중인 새 JVM은 이후에도 닫힌 상태를 유지했다. |
| 지정한 작업자 명시적 재개 | 추가 POST 없이 기존 예약이 `ISSUED`가 됐다. 대기 0·발급 수 1·보유 쿠폰 1·최종 토큰 0을 확인했다. 한 번 더 기동한 뒤에도 본인 상태와 목록의 쿠폰 ID가 같았다. |

Red에서는 기존 Lua가 확인을 끝낸 토큰의 정리 요청에 빈 값을 반환해 두 테스트가 기대한 `1`에서 실패했다. Green에서는 세대·용량·소유자·토큰 중 하나가 다르거나 여러 토큰을 전달하면 저장 상태가 그대로였다. 정확한 토큰을 한 번 제거한 뒤 같은 명령을 반복해도 점유 수는 더 줄지 않았다. 별도 두 소유자 실험에서 다른 소유자의 토큰은 남았고 그동안 명시적 열기도 거절됐다. 프로세스 복구 2개와 실제 Redis 세마포어 9개를 함께 실행해 11개 모두 통과했다. [운영자가 사용하는 SQL·Redis 명령과 재개 순서](coupon-worker-token-recovery.md)

## 5단계 쿠폰 신청 10만 건과 상품·주문 조회 부하

서로 다른 고객 100,000명이 쿠폰 100장인 캠페인에 한 번씩 HTTP POST를 보낸다. 동시에 응답을 기다리는 쿠폰 요청은 최대 64개다. 실제 발급 작업자는 Redis 자리 3개를 쓰며, 상품·주문·쿠폰이 같은 API와 최대 10개 연결의 MySQL 풀을 공유한다. 전용 MySQL 8과 Redis 7.4를 기동하고 사용자 10만 명, 상품 한 개, 그 상품을 담은 주문 한 건을 먼저 만든다. 사용자 헤더는 실험용 식별기로 해석한다. [부하 실험](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponLoadMeasurementTest.java)

1. 상품 상세와 본인 주문 상세를 각각 80회 예열한다. 쿠폰 신청을 진행하는 동안 두 GET을 각각 50ms 간격으로 보내고 HTTP 본문을 다 받을 때까지 시간을 잰다. 응답 코드·본문·ID와 타임아웃을 검사한다.
2. 마지막 신청 응답을 받은 뒤 미발급 예약과 발급 토큰이 모두 0이 될 때까지 기다린다. 같은 앱에서 쿠폰 신청 없이 같은 길이와 시각 간격으로 두 GET을 반복한다. 두 구간에서 대응하는 표본으로 p50·p95·p99를 계산한다. 발송이 밀려 건너뛴 시각과 실제 발송 지연은 응답 지연과 별도로 기록한다.
3. 마감 응답은 HTTP 409와 `COUPON_CAMPAIGN_SOLD_OUT` 본문으로 확인한다. 각 POST가 서버에서 빌린 DB 연결과 실행한 SQL을 응답 코드별로 집계한다. 성공 요청에서 SQL이 관찰되는지 함께 확인하고, Redis 선별 사용자·HTTP 예약증·MySQL 예약 및 실제 쿠폰 소유자를 맞춰 본다.

```sh
./gradlew --offline :apps:commerce-api:couponLoadMeasurement --console=plain
```

원시 집계 JSON은 실행 후 `apps/commerce-api/build/reports/coupon-load/measurement.json`에 생성된다. 별도 측정 작업으로 실행하므로 일반 회귀 검사에 10만 요청이 추가되지 않는다.

### 2026-11-12 측정 결과

두 번의 독립 실행에서 매번 새 MySQL·Redis와 10만 명을 준비했다. 첫 회차는 전체 구간을 측정했고, 재현 회차는 마지막 성공 예약 응답 전후를 추가로 나눴다. 아래 수량은 **회차당 수량**이다.

| 확인 대상 | 두 회차에서 각각 관찰한 결과 |
| --- | --- |
| 고객 응답 | `202 WAITING` 100건, `409 COUPON_CAMPAIGN_SOLD_OUT` 99,900건. 기타 상태와 통신 오류 0건 |
| Redis → MySQL → 고객 대조 | Redis 선별 100명. HTTP 예약증의 (사용자 ID, 예약 ID) 100쌍이 실제 보유 쿠폰을 연결한 DB 조회 결과와 일치. 최종 예약자 100명·발급자 100명·쿠폰 100장·대기 0명 |
| 마감 POST 99,900건의 DB 접근 | 연결을 빌린 요청 0건, 연결 획득 0회, SQL 실행 0회 |
| 성공 POST 100건의 DB 접근 | 100건 모두 DB 연결 사용. 연결 획득 100회, SQL 실행 500회. 백그라운드 작업자의 발급 SQL은 POST별 집계에서 제외 |

1회차는 13.001초(초당 7,692건), 2회차는 13.447초(초당 7,436건)에 신청 응답 10만 건을 받았다. 1회차는 API마다 예정된 261쌍 중 두 구간에 대응한 260쌍, 2회차는 269쌍 중 269쌍을 비교했다. 모든 조회는 HTTP 200과 요청한 상품·주문 ID를 반환했다.

| 회차 · API | p50 평소 → 부하 (ms) | p95 평소 → 부하 (ms) | p95 증가 (ms) | p99 평소 → 부하 (ms) | 오류 평소 → 부하 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 · 상품 상세 | 6.8 → 7.3 | 10.3 → 382.7 | +372.4 | 12.2 → 533.8 | 0 → 0 |
| 1 · 본인 주문 상세 | 9.8 → 12.9 | 16.5 → 316.3 | +299.8 | 19.6 → 534.8 | 0 → 0 |
| 2 · 상품 상세 | 6.9 → 7.5 | 10.3 → 222.1 | +211.9 | 16.6 → 535.0 | 0 → 0 |
| 2 · 본인 주문 상세 | 9.9 → 13.2 | 18.0 → 232.6 | +214.5 | 21.3 → 545.1 | 0 → 0 |

2회차에서 마지막 성공 예약 응답은 시작 후 **0.974초**에 도착했다. 그 전 시작한 조회 20쌍의 p95는 상품 542.0ms·주문 548.7ms였다(같은 구간 평소 9.8ms·14.1ms). 이후 시작한 249쌍의 p95는 상품 15.5ms·주문 23.2ms였다(평소 10.3ms·18.0ms). 경계를 걸친 조회 쌍은 0개였다. 이 시각은 예약증을 받은 시점이며, 발급 작업자는 그 뒤에도 남은 예약을 처리한다.

상품·주문·쿠폰 작업자가 공유하는 Hikari 풀에서 연결을 빌릴 때 걸린 시간의 p95는 1회차 1.5 → 480.0ms, 2회차 1.1 → 403.3ms였다. 풀 타임아웃은 두 회차 모두 0건이었다. 마감 요청이 DB에 닿지 않아도 먼저 선별된 100명의 예약은 같은 캠페인 행에 쓰기 잠금을 걸고, 다른 조회와 연결 풀을 공유한다. 실험은 이때 연결 대기가 증가한 사실까지 확인했다. 행 잠금 대기 시간이나 CPU 사용률을 개별 측정하지 않았으므로 HTTP 지연에서 각각이 차지한 비율은 알 수 없다.

이 비교에서 ‘평소’는 같은 격리 앱에서 쿠폰 신청과 미처리 발급이 없는 상태다. 부하 발생기와 API는 같은 JVM에서 실행했고, 상품 1개와 본인의 기존 주문 1건을 반복 조회했다. 초당 처리량과 지연은 최대 64개 쿠폰 요청이 동시에 진행된 이 조건의 측정값이다.

별도 부하 테스트는 두 회차 모두 통과했다. 후속 회귀는 쿠폰 71개와 API ArchUnit 1개 통과, 실패·오류·건너뜀 0건이었다. API main/test Checkstyle 위반은 0건이었고, 종료 뒤 남은 Testcontainers 컨테이너는 0개였다.

## C08 보완 후 전체 검사

2026-11-13 보완 전 전체 검사는 API 2,162개·streamer 99개였다. 이번에는 잔류 토큰 Redis 테스트와 JVM 종료·DB 롤백 통합 테스트 2개를 추가하고 다음 명령으로 전체 검사를 다시 실행했다.

```sh
./gradlew --offline :apps:commerce-api:check :apps:commerce-streamer:check --rerun-tasks --console=plain
```

| 실행 범위 | 결과 |
| --- | --- |
| commerce-api | 167개 스위트·2,164개 통과, ArchUnit 포함 |
| commerce-streamer | 9개 스위트·99개 통과, ArchUnit 포함 |
| 두 앱 합계 | 2,263개 통과. 실패·오류·건너뜀 각 0개 |
| Checkstyle·종료·정리 | XML 보고서 12개 위반 0건. 13분 5초, 종료 코드 0. 종료 뒤 Testcontainers 컨테이너 0개 |

이번 변경과 검사는 테스트 전용 자원에서 수행했다. 10만 신청 부하는 앞서 기록한 두 회차의 별도 측정이며 이 전체 검사의 테스트 수에 합산하지 않았다.

## 제출 준비

- [x] 캠페인 준비 코드·실제 발급/목록 API·DB 정합성·Redis 수용/Lua·관련 테스트를 준비한다.
- [ ] 설계·API·정책·TDD 기록을 실제 선택·구현과 일치시킨다.
- [x] 실제 검사 범위·결과와 소유 자원 정리를 기록한다.
- [ ] PR에 총량·현재 점유·중복 결과·장애 거절과 재개 조건을 적는다.
- [ ] 기술 글에 선택 이유·비용·반례·관찰을 정리한다.
- [ ] 실제 제출 여부를 별도로 확인한다.
