# W8 계획 — 쿠폰 발급 워커 스레드 보호

작성: 2026-11-10, `volume-8`.
목적: 쿠폰 발급 요청이 몰릴 때 워커 스레드를 보호한다. 아래 네 방식을 각각 도입해 비교한다.

배경: 쿠폰 발급 spike 부하 테스트(`loadtest/`)와 발급 트랜잭션 축소(발급 쿠폰 insert + 조건부 재고 차감만 트랜잭션에 포함).

## 도입할 방식

| # | 방식 | 대기열 |
|---|---|---|
| 1 | 레디스로 직접 구현한 세마포어 | 없음 |
| 2 | Redisson 세마포어 (`RPermitExpirableSemaphore`) | 없음 (pub/sub 으로 깨어남, 순서 보장 없음) |
| 3 | 직접 구현한 fair 세마포어 | 있음 (도착 순서대로) |
| 4 | Redisson 세마포어 async (`RPermitExpirableSemaphore` async API) + 비동기 응답 | 없음 (2번과 같음). 기다리는 동안 워커 스레드를 잡지 않음 |

## 발급 흐름 (큰 틀)

네 방식 모두 아래 흐름 안에서 세마포어 자리만 바꾼다. 방식 전환은 설정이 아니라 코드 교체로 한다.

1. 발급 이력 테이블에서 이 사용자가 이미 발급받았는지 조회한다.
2. 이미 있으면 중복 오류로 리턴한다.
3. 없으면 세마포어 획득을 시도한다. 1번은 실패하면 600ms 뒤 한 번 재시도, 2번은 재시도 없이 최대 600ms 기다린다 (Redisson waitTime).
   3번은 먼저 시도하지 않고 대기열 뒤에 서서 차례를 최대 600ms 기다린다.
   4번은 2번과 같이 기다리되, 워커 스레드는 기다리지 않고 돌아간다 (응답은 허가·발급이 끝난 뒤 비동기로).
4. 그래도 실패하면 리턴한다.
5. 획득하면 발급 로직을 실행한다.
6. 발급 로직이 성공하든 실패하든(예외 포함) permit 을 반납한다.

## 1번 — 레디스 직접 구현 세마포어

구현했다가 2번으로 교체: `RedisCouponIssueSemaphore` 는 커밋 577ed5c 에 남아 있다. 설계 기록은 [design.md DR-60](../week2/design.md).

- 범위: 프로모션마다 키 하나, `coupon-issue:semaphore:{promotionId}`.
- 자료구조: ZSET. member 는 permit 마다 새로 만든 토큰, score 는 만료 시각.
  - 획득 (Lua 하나로 원자 실행): 만료된 토큰 제거 → 남은 개수가 permit 수 미만이면 내 토큰 추가 → 성공 / 아니면 실패.
  - 반납: 내 토큰만 제거한다. 남의 permit 을 반납하지 않는다.
  - 서버가 permit 을 쥔 채 죽으면 lease 가 지나 다음 획득 때 제거된다.
- permit 수: 200 (앱 3대 × 커넥션 풀 100 = 300 보다 작게).
- lease: 2s.
  - 처음 정한 200ms(재시도 300ms)와 400ms(재시도 600ms)는 permit 보유 시간(행 잠금 대기 최대 약 1.5초)보다 짧아,
    실행 중인 permit 이 만료되고 다음 요청이 들어와 MySQL 동시 실행이 300 까지 올랐다.
    2s 에서는 300·400·600 rps 모두 MySQL 실행 중 스레드 최대가 202~203 으로 permit 200 근처에 머물렀다.
  - 결과: `loadtest/issue-20261110-172327` ~ `issue-20261110-175421` (실행별 설정은 커밋 cca3820 메시지).
- 명령은 `masterRedisTemplate` 으로만 보낸다 (레디스 master/replica 구성).
- 리미터 범위: `CouponIssueProcessor.issue` (발급 트랜잭션) 호출 하나만 감싼다.
  - `PromotionFacade.issue` 에서 이 호출 직전에 획득하고, 호출이 끝나면 (커밋·롤백·예외 모두) `finally` 에서 반납한다.
  - 트랜잭션 메서드 안에서 획득하지 않는다. 트랜잭션이 시작되면 커넥션을 이미 잡은 뒤라 보호가 안 된다.
  - 사용자 조회·프로모션 확인·발급 이력 조회는 리미터 밖이다.
- 거절 (재시도까지 실패): 새 `ErrorType.COUPON_ISSUE_BUSY`, HTTP 503 Service Unavailable.
- 이미 발급: 기존 `ErrorType.COUPON_ALREADY_ISSUED` (409).
- Redis 오류: 획득 실패로 본다 (fail-closed). 재시도도 실패하면 503. 반납 오류는 로그만 남긴다.
- lease 시각 기준: Lua 안에서 Redis `TIME`. 앱 서버 시계와 무관하다.

## 2번 — Redisson 세마포어

구현했다가 3번으로 교체: `RedissonCouponIssueSemaphore` 는 커밋 e2f0c9e 에 남아 있다. `RedissonConfig`(modules/redis, `@Lazy`)와 의존성은 남겼다. 설계 기록은 [design.md DR-61](../week2/design.md).

- 범위: 프로모션마다 `RPermitExpirableSemaphore` 하나, `coupon-issue:semaphore:{promotionId}` (1번과 같은 키 이름).
- 획득: `trySetPermits(200)` (키가 없을 때만 설정) → `tryAcquire(waitTime 600ms, lease 2s)`.
  - 허가가 없으면 600ms 까지 기다린다. 반납되거나 가장 빠른 lease 가 만료되면 깨어나 다시 시도한다. 그래도 없으면 재시도 없이 503.
  - 대기열 순서는 보장하지 않는다 (깨어난 스레드끼리 경쟁).
- 반납: 내 permitId 만 `tryRelease`. 이미 만료됐으면 false 이고 예외는 없다.
- lease: 2s. 1번에서 정한 값. 만료 시각은 Redis `TIME` 이 아니라 앱 서버 시계(`System.currentTimeMillis`) 기준이다 (1번과 다름).
- 대기하는 동안 워커 스레드는 잡혀 있다 (최대 600ms). 1번은 600ms sleep 으로 똑같이 잡혀 있었다.
- 명령은 master 로만 보낸다 (`ReadMode.MASTER`). 응답 timeout 은 `datasource.redis.timeout`(300ms).
- Redisson 버전: 3.52.0. 4.x 는 netty 4.2 를 요구하는데 Spring Boot 3.4.4 가 netty 를 4.1.119 로 고정해 3.x 마지막 버전을 쓴다.
- Redis 오류·인터럽트: 획득 실패로 본다 (fail-closed). 반납 오류는 로그만 남긴다.

## 3번 — 직접 구현 fair 세마포어

구현했다가 4번으로 교체: `RedisFairCouponIssueSemaphore` 는 커밋 25b82e0 에 남아 있다. `RedisConfig` 의 pub/sub 연결·리스너 컨테이너(modules/redis)는 남겼다. 설계 기록은 [design.md DR-62](../week2/design.md).

- 범위: 프로모션마다 ZSET 두 개.
  - `coupon-issue:fair-semaphore:{promotionId}:holders`: member 는 허가 토큰, score 는 lease 만료 시각(µs). 1번과 같은 토큰 채우기.
  - `coupon-issue:fair-semaphore:{promotionId}:queue`: member 는 `serverId:토큰`, score 는 대기 마감 시각(µs) = 도착 시각 + wait-time.
    wait-time 이 모두 같아 score 순서가 곧 도착 순서다. 마감 지난 대기자(서버가 죽어 남은 대기자 포함)는 score 로 한 번에 지운다.
- 획득 (Lua 하나): 만료 허가·마감 지난 대기자 제거 → **먼저 시도하지 않고** 큐 뒤에 추가 → 빈 자리만큼 큐 앞을 holders 로 옮기고 각자 서버 채널로 PUBLISH → 나까지 옮겨졌으면 바로 성공.
- 반납 (Lua 하나): 만료 정리 → 내 큐 항목·토큰 제거 → 빈 자리만큼 큐 앞을 옮기고 PUBLISH.
  - 하나만 꺼내지 않는다. lease 만료로 여러 자리가 비면 그만큼 채운다.
- 깨우기: 서버마다 시작할 때 `coupon-issue:fair-semaphore:wake:{serverId}` 하나만 구독한다. 메시지는 토큰이다.
  - 대기 스레드는 토큰별 future 로 기다린다. future 는 획득 Lua 전에 등록해, 큐에 넣고 구독하기 전에 알림이 오는 경쟁이 없다.
  - 구독은 master 단독 연결로 한다. Spring Data Redis 는 master/replica 연결에서 pub/sub 을 지원하지 않는다.
- 타임아웃 (wait-time 600ms): 로컬에서 끝내고 503. 큐 항목은 Redis 마감도 이미 지나 다음 Lua 에서 지워진다.
  - 끝내기 전에 반납을 부른다. 마감 직전에 옮겨졌는데 알림이 유실·지연됐으면 그 허가를 다음 대기자에게 넘기고, 빈 자리도 채운다.
- permit 200, lease 2s, wait-time 600ms (2번과 같음). 큐 길이 상한 없음.
- 시각은 Redis `TIME`. Lua 숫자는 %.14g 로 바뀌어 µs 시각이 잘리므로 score 는 %.0f 문자열로 넘긴다.
- Redis 오류·인터럽트: 획득 실패 (fail-closed). 반납을 불러 마감 전인 큐 항목과 받은 허가를 지운다. 반납 오류는 로그만 남긴다.

## 4번 — Redisson 세마포어 async

구현됨: `RedissonAsyncCouponIssueSemaphore`, `CouponIssueLimiter` 의 스레드 풀. 설계 기록은 [design.md 8-4, DR-63](../week2/design.md).
처음 계획한 4번(bulkhead)을 이 방식으로 바꿨다 (2026-11-12, 사용자 결정).

- 세마포어: 2번과 같은 키 `coupon-issue:semaphore:{promotionId}`, 같은 순서를 async API 로 부른다.
  `trySetPermitsAsync(200)` → `tryAcquireAsync(waitTime 600ms, lease 2s)`. 반납은 `tryReleaseAsync` 이고 기다리지 않는다.
- 비동기 경계: 컨트롤러가 `CompletableFuture` 를 돌려준다 (Spring MVC async). 사용자·프로모션·중복 확인은 워커 스레드에서 동기로 하고, 그 뒤 워커 스레드는 반납된다.
  - 세마포어만 async 로 바꾸고 리미터에서 기다리면 2번과 같다. 그래서 경계를 컨트롤러까지 올렸다.
- 발급 TX 실행: 허가 결과는 Redisson(netty) 스레드로 온다. 거기서 JDBC 를 돌리지 않고 리미터가 가진 스레드 풀로 넘긴다.
  - 스레드 100, 큐 200 (`coupon-issue.limiter.executor.*`). 큐가 넘치면 TX 를 실행하지 않고 허가를 반납한 뒤 503 `COUPON_ISSUE_BUSY`.
  - 풀은 빈으로 두지 않는다 (Executor 빈이 생기면 Boot 의 `applicationTaskExecutor` 자동 구성이 빠진다).
- `spring.mvc.async.request-timeout`: 5s. 넘으면 기존 `Throwable` 핸들러로 500. TX 는 취소되지 않아, 응답은 실패인데 발급은 될 수 있다.
- permit 200, lease 2s, wait-time 600ms (2·3번과 같음). Redis 오류는 fail-closed, 반납 오류는 로그만.

## 미정 항목

- 1번 부하 테스트 조건과 비교 지표 — 나중에 정한다.
- 2번 부하 테스트 (1번과 같은 조건으로 비교).
- 3번 테스트 (단위·통합, `PromotionV1ApiE2ETest` 의 허가 채우기) 와 부하 테스트.
- 4번 부하 테스트 (2·3번과 같은 조건). 스레드 수·큐 크기는 여러 값으로 비교한다.
