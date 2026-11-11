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
| 4 | bulkhead | — |

## 발급 흐름 (큰 틀)

네 방식 모두 아래 흐름 안에서 세마포어 자리만 바꾼다. 방식 전환은 설정이 아니라 코드 교체로 한다.

1. 발급 이력 테이블에서 이 사용자가 이미 발급받았는지 조회한다.
2. 이미 있으면 중복 오류로 리턴한다.
3. 없으면 세마포어 획득을 시도한다. 1번은 실패하면 600ms 뒤 한 번 재시도, 2번은 재시도 없이 최대 600ms 기다린다 (Redisson waitTime).
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

구현됨: `CouponIssueLimiter`, `RedissonCouponIssueSemaphore`, `RedissonConfig`(modules/redis). 설계 기록은 [design.md 8-4, DR-61](../week2/design.md).

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

## 미정 항목

- 1번 부하 테스트 조건과 비교 지표 — 나중에 정한다.
- 2번 부하 테스트 (1번과 같은 조건으로 비교).
- 3·4번은 2번을 마친 뒤 다룬다.
