# 8주차 구현 기록 — DB에서 Redis 판단까지

DB 조건부 UPDATE와 Redis 수량·중복 판단을 구현하고 부하를 측정했다. 통과 요청의 DB 대기와 다른 API의 지연을 관찰하고, 요청별 시간 분해로 지연 구간을 확인했다.

## 1단계: 요청 → RDB

- 캠페인·보유 쿠폰 모델, 발급·목록 API, 조건부 UPDATE 발급 TX, 유일 제약 위반 시 다시 읽기를 구현했다([ADR 0067](../adr/0067-issue-coupon-with-conditional-update-in-db.md)).
- 처음에는 `FOR UPDATE` 잠금과 조건부 UPDATE를 설정으로 바꿔 가며 같은 테스트·부하 실험을 돌렸다. 조건부 UPDATE를 고른 뒤 잠금 방식 코드(`findForUpdate`, 모델의 `recordIssue`, 방식 선택 enum)와 그 테스트를 지웠다.
- 부하 실험 도구: `CouponLoadExperimentTest` + `scripts/week8-coupon-load.k6.js`. 정합성·처리량·응답 시간·Hikari·톰캣·InnoDB 행 잠금·DB UPDATE/INSERT 수·상품 조회·매진 뒤 요청·앱 로그 크기를 기록한다. 결과는 [experiments.md](experiments.md).

## 2단계: 요청 → Redis 발급 판단 → RDB

- 캠페인에 종료 시각(`end_at`)을 추가하고 DB 조건부 UPDATE에 `end_at > 지금` 조건을 더했다.
- `CouponIssueGate`(domain) / `RedisCouponIssueGate`(infrastructure)와 Lua 스크립트 4개(`redis/coupon-gate-*.lua`: 판단, 되돌리기, 남은 수 돌려주기, 준비)를 추가했다.
- `CouponFacade.issue`: Redis 판단 → 통과면 DB 발급 TX → DB 결과에 맞춰 되돌리기. Redis 오류는 503, 준비 안 됨은 503(없는 캠페인은 404), 이미 통과했는데 DB에 쿠폰이 없으면 409 처리 중.
- 관리자 준비 API `POST /api-admin/v1/coupon-campaigns/{campaignId}/issue-gate`, 판단 정보는 종료 시각 + 1시간에 만료.
- Redis 명령 응답 대기 200ms: 공통 `RedisProperties`에 선택값 `commandTimeout`을 더하고, commerce-api의 `application.yml`에서만 지정했다.
- 테스트 시계는 Redis 키 만료가 Redis 서버 실제 시각 기준이라 "지금" 근처로 두었다. 처음에 2026-01-01로 고정했더니 준비 직후 키가 바로 만료돼 테스트가 실패했다.
- 부하 실험 도구에 Redis 컨테이너, 실행마다 준비 API 호출, 409 처리 중 구분, 실험 뒤 Redis 상태 기록을 더했다.

## 1·2단계 실험에서 확인한 결과

- 행 잠금과 조건부 UPDATE를 같은 조건으로 비교해 조건부 UPDATE를 채택했다. 조건부 UPDATE도 행 잠금을 사용하며, 잠금을 쥐는 구간과 대기 비용이 비교의 핵심이었다.
- DB 정합성은 지켰지만 600/s 기준 상품 조회 p95 5,543ms, DB UPDATE 18,007회 중 매진 뒤 UPDATE 13,387회가 관찰됐다.
- Redis 적용 뒤에는 DB UPDATE가 약 4,938회, 매진 뒤 UPDATE가 0회로 줄고 매진 약 2초 뒤 회복했다. 반면 발급 p95 7,138ms·상품 p95 6,570ms로 매진 전 대기는 남았다.
- 전체 조건·결과·관찰 한계는 [experiments.md](experiments.md)의 1·2단계에 있다.

## Redis 단계의 실패 처리 보강

기존 DB 발급·Redis 판단을 유지하면서 다음 경계를 명확히 했다. 현재 정책은 [design.md](design.md), 결정 관계는 [ADR 0074](../adr/0074-confirm-coupon-reservations-after-db-outcome.md)를 따른다.

- `CouponIssueTransaction`: 기존 쿠폰 재확인 → 캠페인 조건부 UPDATE → 쿠폰 INSERT를 REQUIRES_NEW TX로 처리한다. 동일 사용자 경쟁의 UNIQUE 위반이나 마지막 한도의 업무 거절 뒤 TX 밖에서 다시 조회해 기존 쿠폰을 우선한다.
- `CouponFacade`: 사용자 확인·기존 결과·경로 선택·Redis 예약과 DB 결과의 보상을 조율한다. 새 발급 201과 기존 쿠폰 200 모두 실제 DB 결과다. 응답 data는 `{couponId, campaignId, issuedAt, benefitDescription}`다.
- Redis 사용자 기록은 `R:UUID`(이번 실행 예약)와 `A`(DB 발급 확인)다. 확인·보상은 소유한 R만 반영한다. 기존 쿠폰 결과인 경우 예약 차감분만 한 번 복구한다.
- **초기 Redis 단계와 달라진 정책:** DB 결과 불명은 무조건 보상하지 않는다. rollback 확정 또는 TX 본문 진입 전 실패일 때만 자기 예약을 보상하며, 결과 불명·보상 실패는 R을 남긴다. 시간 경과만으로 자동 회수하지 않는다.
- 행사 종료 + 보존 시간(기본 1시간)에 stock/users가 함께 만료되며, 처음 생성되는 사용자 Hash에도 만료를 건다.
- 재준비는 신규 유입 중단·모든 실행과 DB 종료 확인 후 DB 기준으로 한다. 준비 API가 이 운영 전제를 자동 강제하지는 않는다.
- 부하 도구는 `DB_ONLY` / `REDIS_SYNC` 비교를 지원한다. UPDATE 표본은 발급 판단까지 간 요청의 근사이며 전체 DB 조회 수와 구분한다.

## DB·Redis 단계 검증

- 쿠폰 모델·영속성·DB 동시성·Redis Lua·HTTP·복구·DB 결과 불명 분기와 ArchUnit을 포함한 관련 검사 **58개 통과**, 실패·오류·건너뜀 0개.
- commerce-api의 Checkstyle main/test와 commerce-streamer의 테스트 코드 컴파일 통과.
- 계측 추가 전 전체 `:apps:commerce-api:check :apps:commerce-streamer:check` 통과(12분 42초). API 631개·streamer 91개, 실패·오류·건너뜀 0개. 두 앱의 Checkstyle·ArchUnit 포함.
- DB 접근량은 1·2단계에서 비교했고, REDIS_SYNC의 요청별 시간 분해는 아래 계측 실행으로 확인했다.

Redis 오류 테스트 일부는 오류 주입이며 실제 endpoint 중단과 구분한다. DB 완료 불명은 단위 테스트로 확인했고 실제 commit 응답 유실 자체는 재현하지 않았다.

## DB·Redis 발급의 시간대별 대기 분석

600/s 실험의 원본 JSON에서 발급 완료 수·DB UPDATE·커넥션 대기·상품 응답 지연을 시간대별로 분석했다.

Redis 실험에서 1~2초부터 Hikari 대기 159가 관찰됐고, 발급 중에는 대기가 지속됐다. 마지막 신규 발급 응답 약 24.5초 뒤 26~27초에는 Hikari 대기 0·상품 p95 52ms, 27~28초에는 상품 p95 11ms였다. DB만 사용한 실험은 27~28초에도 UPDATE 670회·Hikari 대기 159·상품 p95 1,967ms였다.

원본은 1초 집계와 전체 요약이며 모든 0.5초 원시 표본을 보존하지는 않았다. DB 잠금·Tomcat·Hikari 사용 중의 정확한 발생 순서와 요청별 지연 분해는 미확인이다. 전체 표·읽는 기준·다음 가설은 [실험 기록](experiments.md#2-2-시간대별-기록-읽기--600s에서-대기가-쌓이고-풀리는-과정)에 남겼다.

## 다음 작업

[기존 측정](experiments.md)에서 남은 매진 전 DB 대기·다른 API 영향을 기준으로 분산락·동시 실행 제한·입장 대기열을 검토한다. 현재 학습은 과제의 특정 장치·수치를 필수로 정하지 않고 처리량 제어 관점에서 진행한다. 원인 구간을 관찰한 뒤 다음 후보를 고른다.

## 요청별 커넥션·SQL 시간 분해

실험 전용 `JdbcTimingProbe`·`CouponTimingApplication`과 요청 ID를 추가했다. `COUPON_LOAD_JDBC_TIMING=true`일 때만 별도 JVM의 격리된 테스트 classpath에 계측을 올린다. 정상 앱·API 계약·Redis 예약·DB TX·유입 제한 정책은 변경하지 않았다. 원시 App/DB 표본과 압축 요청별 시간·k6와의 1:1 대응을 보존하고 분석 스크립트를 추가했다.

- 먼저 계측 클래스 미구현에 따른 테스트 컴파일 실패를 확인했고, 커넥션 획득/점유/SQL/commit 분리·예외 보존·ThreadLocal 정리·중복 close 계측을 검증하는 단위 테스트 2개를 통과시켰다.
- 30/s·4초·40장 실제 앱 검사 통과. 신규 발급 40·실제 쿠폰 40, 계측 204건·App/DB 표본 각 9건을 확인했다.
- 600/s·40초·5,000장 첫 진단 실행 통과. 실제 발급 요청 14,373건·상품 682건, 발급 수/쿠폰/사용자/201 모두 5,000.
- 같은 목표 조건의 요청 ID 연결 실행 통과. 발급 요청 13,966건·상품 676건의 총 14,642건을 1:1 연결했고, 실제 쿠폰 5,000장·한도 초과/중복 0건·상품 실패 0건·커넥션 획득 실패 0건.
- 신규 발급의 평균 HTTP 6,576.1ms / filter 처리 1,065.4ms / 커넥션 획득 합계 837.1ms / 점유 합계 225.1ms. 점유 안의 UPDATE 205.9ms는 MySQL 행 잠금 대기 평균 205.7ms와 근접했다. 각 평균의 집계 범위가 달라 순수 잠금 시간을 차감해 계산하지 않는다.
- 같은 요청 HTTP와 filter 처리의 평균 차이 5,510.6ms를 관찰했다. 이를 모두 Tomcat queue 시간으로 부르지는 않으며 filter 바깥·네트워크를 포함한 범위로 기록했다.
- 계측 추가 후 `:apps:commerce-api:test --tests '*Coupon*' --tests 'experiments.coupon.JdbcTimingProbeTest' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest` 통과(43초). 관련 테스트 60개, 실패·오류·건너뜀 0개. 전체 API·streamer 회귀는 이번 계측 변경 뒤 다시 실행하지 않았다.

전체 조건·측정 경계·결론·미확인 범위는 [실험 2-3](experiments.md#2-3-요청별-시간-분해--redis-이후-커넥션-획득과-db-작업)에 있다. 분산락·입장 대기열·rate limit·bulkhead·동시 실행 제한·Kafka는 이후 문제 해결 후보로 유지하며 이번 계측에서 채택하지 않았다.

## 판단 정보를 앱 메모리에 두는 비교

Redis가 필요한 이유를 실험으로 확인하려고, 판단 규칙은 그대로 두고 저장 위치만 바꿀 수 있게 했다.

- `LocalCouponIssueGate`(infrastructure)를 추가했다. Redis Lua와 같은 규칙(수량·중복·`R:UUID`/`A`·확인·보상·준비)을 이 앱의 메모리에서 캠페인별 잠금으로 처리한다. 만료 시각은 쓰지 않고, 상태는 서버마다 따로이며 재시작하면 사라진다.
- 설정 `commerce.coupon.issue-gate.store: REDIS | LOCAL`을 추가했다. 기본은 REDIS이고 `RedisCouponIssueGate`가 선택된다. LOCAL은 서버 한 대 비교용 실험 설정이다.
- 부하 도구에 `COUPON_LOAD_GATE_STORE`를 더했다. LOCAL이면 Redis 상태(`gateAfter`)를 기록하지 않는다.

**검증**

- `LocalCouponIssueGateTest` 4개 통과. 준비 전 판단 없음, 남은 수·이미 발급·예약 중·종료 판단, 보상·기존 결과의 한 번만 복구, 100명이 동시에 10장이면 정확히 10명 통과를 확인했다.
- `CouponIssueGateTwoServersTest` 4개 통과. 서버 두 대를 판단 객체 두 개로 흉내 내고 남은 2장에 A·C(서버 1)·B(서버 2)를 동시에 보내면 앱 메모리는 셋 다 통과, Redis는 둘만 통과하고 하나는 매진이다. 같은 사용자가 다른 서버에 다시 요청하면 앱 메모리는 또 통과, Redis는 처리 중으로 걸러진다.
- 기존 `RedisCouponIssueGateTest` 8개 통과. 세 클래스 합계 16개, 실패·오류·건너뜀 0개.
- 서버 한 대 부하 실험(600/s·40초·5,000장)을 LOCAL·REDIS 각 1회 실행했다. 결과와 해석은 [실험 2-0](experiments.md#2-0-거를-곳은-왜-redis인가--앱-메모리와-비교)에 있다.
- 이번 변경 뒤 `:apps:commerce-api:test --tests '*Coupon*' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest` 통과. 관련 테스트 64개, 실패·오류·건너뜀 0개. commerce-api 전체 `check`는 실행하지 않았다.

## DB 앞 대기형 분산락·세마포어 비교

"기다리면 톰캣 스레드를 쥔다"는 추론을 확인하려고, DB 발급 TX 앞에서 자리를 기다리는 장치를 실험 설정으로 붙였다.

- `CouponIssueLimiter`(domain)와 구현 세 개(infrastructure)를 추가했다. `NoCouponIssueLimiter`(기본), `RedissonLockCouponIssueLimiter`(캠페인당 `RLock`, 만료 대신 watchdog), `RedissonSemaphoreCouponIssueLimiter`(캠페인당 `RSemaphore`, 처음 쓸 때 자리 수 설정)다.
- 의존성 `org.redisson:redisson:3.45.1`을 추가했다. Redisson 클라이언트는 `CouponIssueLimitRedissonConfig`가 제한 모드가 NONE이 아닐 때만 만들고, 발급 판단과 같은 Redis master를 쓴다.
- 설정 `commerce.coupon.issue-limit.mode: NONE | LOCK | SEMAPHORE`, `permits`(기본 3), `wait-timeout`(기본 60초)을 추가했다.
- `CouponFacade`는 Redis 판단 통과 뒤 DB 발급 TX 앞에서 자리를 기다리고, TX가 끝나면 같은 스레드에서 돌려준다. 시간 안에 못 얻거나 Redis 오류면 TX에 들어가지 않은 실패로 보고 예약을 돌린 뒤 503이다. 반환 실패는 DB 결과를 바꾸지 않고 로그만 남긴다.
- 부하 도구에 `COUPON_LOAD_LIMIT`·`COUPON_LOAD_LIMIT_PERMITS`를 더했다. 결과 JSON을 먼저 저장한 뒤 정합성을 검사하도록 순서를 바꿨다. 검사 기준은 바꾸지 않았다.

**검증**

- `CouponIssueDistributedLockIntegrationTest` 2개 통과. 한 실행이 TX 안에 있는 동안 다음 실행은 TX에 들어가지 않고 기다렸다가 들어간다. 대기 시간이 지나면 TX 없이 예약을 돌리고 503이다.
- `CouponIssueSemaphoreIntegrationTest` 1개 통과. 자리 2개일 때 세 번째 실행은 기다렸다가 들어간다.
- `CouponFacadeOutcomeTest` 2개 통과(생성자 변경 반영).
- 부하 실험 분산락·세마포어 3자리 각 1회. 두 실행 모두 정합성 검사(DB 발급 수 = 201 받은 사용자 수)에서 실패로 끝났다. 한도 초과·중복은 없고, 서버가 기다리는 동안 k6(15초 제한)가 먼저 포기한 요청이 나중에 발급됐다. 결과와 해석은 [실험 2-4](experiments.md#2-4-db-앞에서-들어가는-수를-묶으면--분산락세마포어-기다리기--바로-거절)에 있다.
- 이번 변경 뒤 `:apps:commerce-api:test --tests '*Coupon*' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest` 통과. 관련 테스트 69개, 실패·오류·건너뜀 0개. commerce-api 전체 `check`는 실행하지 않았다.

## 바로 거절 세마포어와 자리 없음 429

- 세마포어 대기 시간 0초로 바로 거절하는 방식을 측정했다. 부하 도구에 `COUPON_LOAD_LIMIT_WAIT`와, 자리 없음으로 거절됐는데 그 뒤에 시작한 요청이 201을 받은 경우를 세는 `limitRejections`를 더했다.
- `CouponIssueSemaphoreFailFastIntegrationTest` 1개 통과. 쿠폰 10장·자리 3개에서 1~3번이 자리를 쥔 동안 온 4번은 거절되고(쿠폰 7장 남음), 그 뒤에 온 5번은 쿠폰을 받는다.
- 부하 결과는 [실험 2-4](experiments.md#2-4-db-앞에서-들어가는-수를-묶으면--분산락세마포어-기다리기--바로-거절)에 있다. 측정 당시 거절은 503이었다.
- 측정 뒤 자리 없음 거절을 `ErrorType.TOO_MANY_REQUESTS`(429)로 바꿨다. 문구 상수는 `CouponRejections.LIMIT_NO_SEAT`다. 자리 확인 자체가 안 되는 경우(`LIMIT_UNAVAILABLE`)는 503을 유지한다. 기본 설정(NONE)에서는 이 경로를 타지 않는다.
- 전체 쿠폰 테스트에서만 세마포어 테스트가 실패했다. 컨텍스트가 바뀌면 테이블이 새로 만들어져 캠페인 ID가 다시 쓰이고, 자리 수가 다른 테스트가 남긴 세마포어 키를 이어 쓴 것이 원인이었다. 테스트 지원 클래스가 캠페인을 만들 때와 정리할 때 그 ID의 락·세마포어 키를 지우도록 고쳤다.
- 이번 변경 뒤 `:apps:commerce-api:test --tests '*Coupon*' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest` 통과. 관련 테스트 70개, 실패·오류·건너뜀 0개. commerce-api 전체 `check`는 실행하지 않았다.

## Redis 판단을 DB 조회보다 먼저 ([ADR 0075](../adr/0075-judge-in-redis-before-any-db-lookup.md))

- `CouponFacade.issue`의 REDIS_SYNC 경로를 바꿨다. ID 형식 확인 뒤 바로 Redis Lua로 판단하고, 매진·종료는 DB 없이 409, 이미 통과한 사용자는 DB에서 쿠폰을 읽어 200 또는 409 처리 중이다. Redis를 통과한 요청만 사용자를 확인하고, 없는 사용자면 예약을 돌린 뒤 404다. Redis 판단이 실패하면 이미 받은 쿠폰만 DB에서 찾아 200, 없으면 503이다.
- DB_ONLY 경로(사용자 확인 → 기존 쿠폰 조회 → 발급 TX)는 기준선이라 바꾸지 않았다. 예전 `reserve` 메서드는 지웠다.
- `CouponRedisIssueIntegrationTest`에 2개를 더했다. 매진 요청은 사용자 확인·기존 쿠폰 조회·발급 TX를 하지 않는다. 없는 사용자는 Redis를 통과해도 404이고 남은 수가 돌아온다.
- 이번 변경 뒤 `:apps:commerce-api:test --tests '*Coupon*' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest` 통과. 관련 테스트 72개, 실패·오류·건너뜀 0개. commerce-api 전체 `check`는 실행하지 않았다.
- 새 구조로 2-3과 같은 조건의 부하 실험을 1회 실행했다. 앞의 실험(2-0~2-4)을 다시 돌리지 않은 이유와 결과는 [실험 2-5](experiments.md#2-5-redis를-db-조회보다-먼저-보게-하면--lua-앞-db-조회-제거)에 있다.

## 부하 도구: 서버 작업 종료 대기와 응답 대응 지표

검토에서 두 가지를 지적받아 도구를 바꿨다. 서비스 코드는 바꾸지 않았다.

- k6가 끝난 뒤에도 표본을 계속 모으고, Hikari 사용 중 0·Tomcat 사용 중 1 이하·쿠폰 수 변화 없음이 3초 이어지면 서버 작업 종료로 본다. 최대 120초(`COUPON_LOAD_DRAIN_SECONDS`)이며 결과에 `drain`(drained, 부하 종료 시각, 작업 종료 시각, 마지막 Tomcat·Hikari 값)을 남긴다. 분산락 실행처럼 관찰 종료 때 서버가 처리 중이던 값을 최종값으로 읽지 않기 위해서다.
- 정합성 검사를 나눴다. DB 정합성(발급 수 = 쿠폰 행 = 서로 다른 사용자, 한도 이하)은 그대로 실패로 처리한다. "DB 발급 수 = 성공 응답 사용자 수"는 클라이언트 시간 초과로 달라질 수 있어 실패에서 빼고 `issuedWithoutSuccessResponse`로 센다. 이유와 영향은 [실험 기록의 숫자를 세는 기준](experiments.md#숫자를-세는-기준)에 적었다.
- 짧은 실제 앱 실행(REDIS_SYNC, 30/s·4초·40장)으로 확인했다. 부하 종료 4.3초, 서버 작업 종료 5.3초(`drained=true`), 발급 40 = 쿠폰 행 40 = 성공 응답 40, 응답 못 받은 발급 0. `:apps:commerce-api:checkstyleTest` 통과.

## 작업 큐 모드 QUEUE ([ADR 0076](../adr/0076-accept-coupon-issue-into-redis-stream-and-save-by-worker.md))

- 정책은 구현 전에 정했다: 202 + 상태 조회, 종료 직전 접수는 종료 뒤 처리돼도 발급, 확정 거절은 취소·일시 오류는 재시도, Worker는 앱 안 1개.
- Redis: `coupon-gate-enqueue.lua`(판단 + XADD), `coupon-queue-reset.lua`(큐 비우기 + 소비자 그룹 생성). `CouponIssueGate`에 `enqueue`·`state`, 새 포트 `CouponIssueJobs`(reset·poll·acknowledge)와 `RedisCouponIssueJobs`. 앱 메모리 판단은 작업 큐를 지원하지 않는다.
- 발급: `CouponIssueTransaction.issueAccepted`(접수 시각으로 종료 판단·발급 시각). 취소 기록 `CouponIssueCancellationModel`(`coupon_issue_cancellation`, 캠페인·사용자 유일).
- `CouponIssueWorker`(application)와 `CouponIssueWorkerScheduler`(interfaces/scheduling, QUEUE이고 `commerce.coupon.issue-queue.worker.enabled`일 때만). `CouponFacade`에 QUEUE 접수 경로와 `getIssueStatus`, 준비 때 작업 큐 초기화. 컨트롤러는 접수면 202, 상태 조회 `GET .../issues/me`를 더했다. `CouponIssueResult`는 CREATED·EXISTING·PENDING 상태를 가진다.
- 부하 도구: QUEUE 모드, 202를 "받음"으로 셈, 접수→저장 시간(`acceptToSaved`), 실험 뒤 큐 상태(`queueAfter`).
- `CouponQueueIssueIntegrationTest` 9개를 먼저 쓰고 통과시켰다. 이후 `:apps:commerce-api:test --tests '*Coupon*' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest` 통과. 관련 테스트 81개, 실패·오류·건너뜀 0개(Checkstyle은 안 쓰는 import 1건을 고친 뒤 통과). commerce-api 전체 `check`는 실행하지 않았다.
- 부하 실험 1회(600/s·40초·5,000장). 결과는 [실험 2-6](experiments.md#2-6-db-저장을-요청-밖으로--작업-큐redis-stream--worker-1개)에 있다.

## Worker 묶음 저장

- `CouponIssueTransaction.issueAcceptedBatch`: 가져온 작업을 한 TX에서 작업마다 사용자 확인(저장소 조회) → 기존 쿠폰 확인 → 조건부 UPDATE → INSERT. 확정 거절은 예외 없이 작업별 결과(`CouponIssueOutcome`)로 돌려준다.
- `CouponIssueWorker`: `save-in-batch`(기본 true)면 묶음 TX로 저장한다. 묶음 TX가 실패로 확정되면 한 건씩 처리로 넘기고, 결과를 모르면 모두 다시 처리한다. 확정·처리 완료는 작업마다 한다.
- 부하 도구: `COUPON_LOAD_WORKER_SAVE_IN_BATCH`, `COUPON_LOAD_WORKER_BATCH_SIZE`.
- `CouponQueueBatchIssueIntegrationTest` 5개를 먼저 썼다(한 TX 저장, 없는 사용자 섞임, DB 한도 넘침만 취소, 묶음 실패 재처리, 묶음 저장 뒤 확정 실패 재처리에서 수량 유지). 기존 `CouponQueueIssueIntegrationTest`는 `save-in-batch=false`로 한 건씩 경로를 본다.
- `:apps:commerce-api:test --tests '*Coupon*' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest` 통과. 관련 테스트 86개, 실패·오류·건너뜀 0개. commerce-api 전체 `check`는 실행하지 않았다.
- 부하 실험 1회(10건 묶음). 결과는 [실험 2-7](experiments.md#2-7-worker-묶음-저장--가져온-작업-10건을-한-tx로)에 있다.

## 묶음 안 발급 수 한 번에 +N, 쿠폰 INSERT 한 문장

- `UserRepository.findActiveIds`, `CouponRepository.findAllByCampaignIdAndUserIds`·`insertAll`(JDBC 여러 행 INSERT, UTC), `CouponCampaignRepository.tryRecordIssues`(`issued_count + N <= total_quota`일 때만 +N)를 더했다.
- `issueAcceptedBatch`를 고쳤다. 캠페인별로 사용자·기존 쿠폰을 한 번씩 조회하고, 새 쿠폰이 모두 한도 안이면 +N 한 번 + INSERT 한 문장, 아니면 쿠폰마다 조건부 UPDATE로 나눈다. 새로 만든 쿠폰의 ID는 돌려받지 않는다(Worker는 확정에 ID가 필요 없다).
- `CouponQueueBatchIssueIntegrationTest`에 확인을 더했다. 다섯 건이면 `tryRecordIssues(+5)` 한 번, 쿠폰마다의 UPDATE 없음, 발급 수 5. 한도에 닿으면 한 번에 올리기가 실패하고 쿠폰마다 나눠 2장 발급·1장 매진.
- `:apps:commerce-api:test --tests '*Coupon*' --tests '*User*' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest` 통과. 관련 테스트 91개, 실패·오류·건너뜀 0개. commerce-api 전체 `check`는 실행하지 않았다.
- 부하 실험 10건·50건 각 1회(50건 첫 실행은 PC 정지로 제외하고 다시 실행). 결과는 [실험 2-8](experiments.md#2-8-묶음-안에서도-한-줄은-한-번만--발급-수-한-번에-n-쿠폰-insert-한-문장)에 있다.

## 작업 큐 장애 경계 보강 ([ADR 0077](../adr/0077-keep-queue-results-stable-across-retries-and-failures.md))

검토 [추가 확장]의 P1·P2를 반영했다. 정한 정책: 미완료 작업은 다 처리될 때까지 보존, 취소된 사람은 재신청 불가(C 표시, 정책), 연결 장애는 간격을 늘려 자동 재시도·특정 작업만 N번 실패하면 보류함.

- 문서: design.md의 동기 모드 한정 문구, "발급되었습니다" → "접수됨·발급 처리 중".
- Lua: `coupon-gate-enqueue.lua`(쓰기 전 jobs 타입 확인, C면 CANCELLED), `coupon-queue-reset.lua`(만료 제거), `coupon-gate-cancel-final.lua`(R이면 C + 수량 복구), `coupon-queue-cleanup.lua`(종료·안 읽은 작업 0·pending 0·보류 0일 때만 정리).
- 포트·구현: `Decision.CANCELLED`, `CouponIssueGate.cancelFinally`, `CouponIssueJobs.reset(campaignId)`·`hold`. `RedisCouponIssueJobs`는 처리 목록 등록을 큐 생성보다 먼저 하고, 읽을 작업이 없으면 정리를 시도한다.
- 쿠폰에 `reservation_id`(nullable)를 더했다. `CouponInfo.reservationId`는 응답에 내보내지 않는다. 여러 행 INSERT에도 포함한다.
- `CouponIssueTransaction`: `issueAccepted`에 예약 값 인자, 묶음은 취소 기록 → 기존 쿠폰 → 사용자 순서. `CouponIssueCancellationRepository.findAll`.
- `CouponIssueWorker`를 다시 썼다: 단건도 같은 순서, 취소는 `cancelFinally`, 연결 장애 분류(`DataAccessResourceFailureException`·`TransientDataAccessException`·`CannotCreateTransactionException`)와 backoff(`backoff-initial` 100ms·`backoff-max` 30s), 그 밖의 실패 횟수(`max-attempts` 5) 뒤 보류, 확정 불일치 로그.
- `CouponFacade`: 접수의 CANCELLED → 409, QUEUE 준비 때 `jobs.reset(campaignId)`, QUEUE 판단 정보 보존 7일.
- 처음 실행에서 설정 파일 들여쓰기 오류로 모든 컨텍스트가 뜨지 못해 고쳤다(`fixed-delay-ms` 위치).
- `CouponQueueFailureBoundaryIntegrationTest` 9개를 먼저 썼다. 기존 작업 큐 테스트는 `issueAccepted` 인자 5개와 재시도 간격 0에 맞췄다.
- `:apps:commerce-api:test --tests '*Coupon*' --tests '*User*' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest` 통과. 관련 테스트 100개, 실패·오류·건너뜀 0개. commerce-api 전체 `check`는 실행하지 않았다. 장애 주입 실험은 아직 하지 않았다.

## 현재 위치

### 지금까지 온 길

| 단계 | 바꾼 것 | 측정으로 확인한 문제 → 다음 단계로 간 이유 |
| --- | --- | --- |
| DB만 | 조건부 UPDATE·UNIQUE로 한도·1인 1매 보장 | 같은 캠페인 행 앞에서 줄을 서 Tomcat·커넥션이 묶이고 상품 조회도 느려짐 |
| Redis 판단 (REDIS_SYNC) | Lua로 남은 수·사용자를 먼저 판단, DB 조회보다 앞 | 매진 뒤 요청은 DB에 안 가지만, 통과한 요청은 여전히 DB 앞에서 기다림 |
| DB 앞 대기 제한 (비교) | 분산락·세마포어, 기다림 / 바로 거절(429) | 기다리면 Tomcat이 묶이고, 바로 거절하면 쿠폰이 남았는데 먼저 온 사람이 떨어짐 |
| 작업 큐 (QUEUE) | Lua에서 판단 + Stream 등록 → 202, Worker 저장 | Tomcat은 풀렸지만 저장이 느림(접수 → 저장 p95 42.5초) |
| Worker 묶음 저장 | 한 TX·발급 수 +N·여러 행 INSERT | 접수 → 저장 p95 5.6초(10건 묶음) |
| 장애 경계 (ADR 0077) | 큐 만료 없음, 재처리에서 확정 결과 유지, 보류함, 연결 장애 backoff | 코드 검토로 찾은 유실·뒤집힘 경로를 테스트로 막음 |

## 확장: 다시 풀리지 않는 마감의 로컬 캐시 ([ADR 0078](../adr/0078-remember-final-closure-in-app-memory.md))

- Lua: stock에 `pending`(저장 전 예약 수)을 더했다. enter·enqueue는 예약 때 +1, confirm·cancel·cancel-final은 자기 예약이 바뀔 때 -1, prepare는 0. 남은 수 0이고 pending 0이면 `SOLD_OUT_FINAL`.
- `CouponIssueGate.Decision.SOLD_OUT_FINAL`, `CouponClosedCampaigns`(서버 메모리, 기억 10초, `commerce.coupon.closed-cache.enabled·ttl`), `CouponRejections.CLOSED_SOLD_OUT·CLOSED_ENDED`.
- `CouponFacade`: REDIS_SYNC·QUEUE 신청은 기억한 마감이면 Redis 전에 409. `SOLD_OUT_FINAL`·`ENDED`를 받으면 기억하고 이번 요청은 Redis 판단대로 답한다. 준비 API는 기억을 지운다.
- 테스트를 먼저 더했다: `CouponClosedCampaignIntegrationTest` 4개, `CouponClosedCampaignsTest` 3개. `CouponFacadeOutcomeTest`는 생성자 인자(캐시 끔)만 맞췄다.
- `:apps:commerce-api:test --tests '*Coupon*' --tests '*User*' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest` 통과. 관련 테스트 107개, 실패·오류·건너뜀 0개. REDIS_SYNC 모드의 최종 매진 기억은 별도 테스트가 없다. 부하 실험은 하지 않았다.

## 유입 올려 보기 (실험 2-9)

- 부하 도구가 k6 요약에서 실제 송신량·초당 송신·`dropped_iterations`·VU 최대를 `generator`로 남기게 했다.
- QUEUE 묶음 10건으로 1,000/s·1,500/s를 각 1회 40초 실행했다. 두 조건 모두 시작하지 못한 요청 0, 정합성 유지. 입구(접수 p95 4·10ms, Tomcat 최대 5·14, 상품 p95 14·19ms)는 버텼고 접수 → 저장 p95가 10.7·13.5초로 10초 목표를 넘었다.
- "이 PC로는 600/s가 한계"는 DB 단계 결과를 넓힌 가정이었고, 응답이 짧아진 작업 큐 구조에서는 맞지 않았다. 앞 단계 결과와의 관계는 [실험 2-9](experiments.md)에 적었다.
- 묶음 50건으로 1,000/s·1,500/s를 각 1회 실행했다([실험 2-10](experiments.md)). 접수 → 저장 p95는 1,000/s 6.2초(목표 안), 1,500/s 11.0초(목표 넘음). 코드 변경 없음.

## Kafka(OUTBOX) 경로를 구현했다가 되돌림

접수 기록 + outbox + relay → Kafka → commerce-streamer 묶음 저장을 단계별로 구현하고 테스트했다. 이번 과제 범위를 Kafka 없이(Redis Stream 작업 큐 + Worker) 대기열·circuit breaker·rate limit·bulkhead 검토로 정하면서 코드·테스트·ADR을 되돌렸다. 그림과 구현하며 얻은 결론(앞으로 채우기, 시간 기반 반환의 위험, 저장 담당 하나)은 [design.md 9절](design.md)에 남겼다.
