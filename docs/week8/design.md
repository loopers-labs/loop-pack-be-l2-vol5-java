# 쿠폰 발급 수용량 통제 설계 — Week 8

> 상태: **구현 완료 (기술 글 제외)**. Redis Set 세마포어로 신규 발급의 동시 실행 수(C)를 통제하고, 한도·중복은 DB가 지킨다.
> 이 문서는 구현된 결과를 적는다. 구현 전에 정한 기준 문서는 저장소 밖의 `coupon-design-spec.md`이며, 달라진 점은 7절에 모았다.

## 0. 목표와 범위

| 목표 | 수단 | 보장의 근거 |
|---|---|---|
| 1. 신규 발급 시도의 부하가 바로 DB로 가지 않게 | Redis Set 세마포어(최대 C), Rate limiter | 정상 시 Lua 원자 수용, 장애 시 로컬 상한 |
| 2. Redis 장애에도 신규 발급 유지 | CircuitBreaker, DB 직행, 자동 재개 | Redis는 원본이 아니라 수용 장치 |
| 3. 동시 경쟁에서 한도 초과 0 | 조건부 `UPDATE` | DB의 원자적 갱신 |
| 4. 1인 1장, 중복 없음 | `UNIQUE(campaign_id, user_id)` | DB 유일 제약 |

- 두 한도를 분리한다. C(지금 진행할 수 있는 실행 수)는 Redis가, 총량(`total_quota`)과 중복은 DB가 지킨다. 자리를 반환해도 쿠폰 재고는 늘지 않는다.
- Redis 상태가 틀려도 한도 초과와 중복 발급은 생기지 않는다. 일시적으로 넘을 수 있는 것은 동시 실행 수 C뿐이다.
- 범위 밖: 쿠폰 사용·할인 적용, 알림, 대기실·비동기 worker·대기열, 캠페인 관리 화면, 실제 인증, 7주차 주문 이벤트와의 연결.
- 철회된 규칙: "Redis 접근 실패 시 신규 발급을 시작하지 않는다"는 따르지 않는다. **Redis가 내려가도 신규 발급은 계속된다.**

## 1. 요청 흐름

```
POST /api/v1/coupon-campaigns/{id}/issues  (X-USER-ID)
  Rate limiter 필터 ── 초과 ─→ 429 RATE_LIMITED + Retry-After
  이미 받은 쿠폰? (DB 읽기) ── 있음 ─→ 200 + 같은 couponId (자리 차지 없음)
  세마포어 획득 (Redis Lua)
     FULL ─→ 429 NO_PERMIT + Retry-After (DB 진입 없음)
     UNCERTAIN(오류·timeout) / SKIPPED(breaker 열림) ─→ 직행: 로컬 상한 획득 ── 초과 ─→ 429 NO_PERMIT
     ACQUIRED ─┐
  발급 TX (DB) ←┘ → 201 · 200 · 409 SOLD_OUT · 409 CAMPAIGN_INACTIVE · 404 · 503
  finally: 자기 token 반환 (TX가 끝난 뒤)
```

- Redis 호출은 DB 트랜잭션 밖에서만 한다. Facade는 트랜잭션을 열지 않는다.
- 반환해도 쿠폰과 `issued_count`는 되돌리지 않는다.

## 2. 구성 요소

| 계층 | 클래스 | 책임 |
|---|---|---|
| interfaces | `CouponIssueRateLimitFilter` | 발급 POST의 전역 속도 제한, `RATE_LIMITED` |
| interfaces | `CouponCampaignV1Controller` / `CouponV1Controller` | 발급 201·200, 내 목록. userId는 `X-USER-ID`로만 받는다 |
| interfaces | `CouponAdmissionRecoveryScheduler` | 5초마다 재개 확인 → 자기 token 정리 |
| application | `CouponIssueFacade` | 기존 쿠폰 확인 → 자리 획득 → 발급 → 반환, 직행 분기 |
| application | `SemaphoreGate`(인터페이스), `LocalLimiter` | 수용 계약, 직행 경로의 JVM 세마포어(C) |
| domain | `CouponIssueService` / `CouponIssueTransactions` | 발급 규칙 / 트랜잭션 단위(별도 빈) |
| domain | `CouponQueryService` | 내 쿠폰(DB만 읽음) |
| infrastructure | `RedisSemaphoreGate` | Lua 획득, 자기 token 반환, breaker, 재개, token 정리 |

## 3. DB 규칙

- `coupon_campaign(total_quota, issued_count, active)`, `coupon(campaign_id, user_id, benefit_description, issued_at)`. `UNIQUE(campaign_id, user_id)`, 내 목록 인덱스 `(user_id, issued_at DESC, id DESC)`.
- 발급 TX(timeout 3초): 쿠폰 `INSERT`(유일 제약) → `UPDATE … SET issued_count = issued_count + 1 WHERE id = ? AND active = 1 AND issued_count < total_quota`. 영향 행 0이면 INSERT까지 롤백한다. INSERT를 먼저 해서 중복 요청이 한도 행의 락을 기다리지 않는다.
- 유일 제약 예외는 롤백 후 **새 트랜잭션**에서 기존 쿠폰을 조회한다. 쿠폰이 실제로 있을 때만 중복(200)으로 본다. 다른 제약·연결 오류는 503이다.
- 이미 받은 사용자는 품절·비활성 뒤에도 같은 결과(200)를 받는다.

## 4. Redis 수용

- 키: `admission:coupon-issue:permits`(활성 token Set), `…:epoch`(상태 유실 감지 표지), `…:probe`(재개 전 왕복 확인). 전용 master 연결, String 직렬화.
- 획득은 Lua 한 번(`SISMEMBER`·`SCARD`·`SADD`). 반환은 `SREM <자기 token>` 하나. token은 `instanceId:UUID`이고 TTL로 자동 회수하지 않는다.
- 유효 용량 = C − (재개 때 Set에서 못 찾은 진행 중 실행 수) − (직행으로 진행 중인 실행 수). 1 미만이면 Redis를 호출하지 않고 자리 없음이다.
- 응답을 못 받은 획득(UNCERTAIN)과 반환 실패는 자기 token으로 기록해 두었다가 복구 뒤 정리한다. 기동 뒤 한 번 `instanceId:` 접두어로 이전 프로세스의 고아 token도 정리한다. 진행 중인 자기 실행의 token은 Redis 호출 전에 진행 중 목록에 넣어 지우지 않는다.

## 5. 장애와 재개

- 모든 Redis 호출은 CircuitBreaker(최근 10회·실패율 50%·최소 5회·시험 호출 3회, 코드로 조립)를 지난다. 열리면 `DEGRADED`가 되어 Redis를 호출하지 않고(`SKIPPED`) 직행한다.
- 기동 때 Redis를 쓰기·읽기로 확인해 시작 모드를 정한다(실패하면 직행으로 시작).
- **자동 재개**(`coupon.admission.auto-resume`, 원문의 수동 재개를 바꿨다): 최소 대기(5초) 뒤 (1) 쓰기·읽기 왕복 (2) sentinel 유실 감지 (3) 자기 token 정리 (4) 진행 중 실행의 용량 차감을 모두 마쳐야 돌아온다. 돌아온 뒤에는 breaker 시험 호출 3회를 통과해야 닫힌다. 하나라도 실패하면 직행을 유지하고 최소 대기부터 다시 센다. `auto-resume=false`이면 `approveResume()`으로 사람이 확인하기 전에는 돌아오지 않는다.
- 단일 인스턴스 전제다. 여러 인스턴스이면 재개 직후 짧게 C를 넘을 수 있고, 감지하지 못한 상태 유실까지 안전한 분산 세마포어라고 주장하지 않는다.

## 6. 응답 규칙

| 상황 | 응답 | DB 신규 효과 |
|---|---|---|
| 최초 발급 | 201 + couponId | 쿠폰 1, 발급 수 +1 |
| 같은 사용자 재요청 | 200 + 같은 couponId | 0 |
| 품절 / 비활성 / 없는 캠페인 | 409 `SOLD_OUT` / 409 `CAMPAIGN_INACTIVE` / 404 | 0 |
| 속도 제한 초과 | 429 `RATE_LIMITED` + `Retry-After`(한도가 다시 채워질 때까지) | 0 |
| 세마포어 자리 없음(직행 시 로컬 상한 초과) | 429 `NO_PERMIT` + `Retry-After: 1` | 0 (DB 진입 없음) |
| Redis 장애 | 정상 응답(세마포어만 건너뜀) | 정상 발급 |
| DB 장애·락 대기 3초 초과 | 503 | 0, 자리 반환 |

429·503과 `Retry-After`는 쿠폰 확보나 나중 발급의 보장이 아니다.

## 7. 기준 문서와 달라진 점·구현에서 정한 값

| 항목 | 내용 |
|---|---|
| 자동 재개 | 원문의 "건강 확인만으로 열지 않는 수동 재개"를 자동 재개로 바꿨다(위 5절). 근거는 목표 2와 운영 부담이다 |
| 직행 경로의 시점 | 설계서의 6단계(breaker·직행) 일부를 5단계(발급 API 연결)에 먼저 넣었다. Redis 실패 때 발급을 멈추지 않게 하려는 것이다 |
| `capacity` 기본값 | 10. 설계서가 "미정, DB 풀 이하"로만 정해 test DB 풀(10) 이하로 잡은 제안값이다 |
| Rate limiter 한도 | 초당 300건(전역). 설계서가 "미정"이라 잡은 제안값이며 부하 측정으로 조정한다. test 프로파일에서는 끈다 |
| Redis command timeout | 기존 설정(300ms, 상품 캐시와 공유)을 그대로 쓴다. 설계서 제안은 200ms였다 |
| 사전 거절 위치 | 품절·비활성·없는 캠페인 확인은 자리 획득 뒤 서비스 안에서 한다(설계서 흐름도와 같다). 그래서 품절 뒤에도 요청이 자리를 점유하고, 부하가 크면 409 대신 429가 나갈 수 있다 |
| 공유 파일 | `ErrorType`에 오류 타입을 추가하고 `ApiControllerAdvice`가 `NO_PERMIT`일 때 `Retry-After`를 붙인다 |
| 의존성 | `resilience4j-ratelimiter`(기존 2.2.0과 같은 버전)를 추가했다 |
| 기준 문서의 html | md보다 오래된 판본이라 철회된 규칙이 남아 있다. md를 따랐다 |

## 8. 검증 (실제 MySQL·Redis, mock 없음)

| 테스트 | 확인하는 것 |
|---|---|
| `CouponRepositoryTest` | UNIQUE 위반, 한도에서 멈추는 UPDATE, 비활성 캠페인, 목록 인덱스가 `(user_id, issued_at DESC, id DESC)`로 만들어지고 목록 조회가 쓰는지 |
| `CouponIssueIntegrationTest` | 정상·재요청, 동시 동일 사용자(DB 효과 1), 총 5장·8명(발급 5·품절 3·쿠폰 5·발급 수 5), 마지막 한 장 경쟁, INSERT 롤백 |
| `CouponIssueDbFailureTest` | 캠페인 행 락 대기가 3초에 끊겨 503, 컬럼 제약 위반을 중복 성공으로 바꾸지 않고 503, 쓰기·자리 없음 |
| `CouponAdmissionTest` | C=3 수용, 12개 동시 요청에서 정확히 3개, 중복·모르는 token 반환, TTL 없음 |
| `CouponAdmissionRecoveryTest`, `CouponAdmissionRedisOutageTest` | 고아 token이 용량을 먹는 문제와 정리, 남의 token 보존, 장애 중 반환 실패의 복구 뒤 정리 |
| `CouponIssueV1ApiE2ETest` | 201·200·409·404·400, C=3 수용 실험(Redis 획득 직후 DB 진입 전 barrier로 세 실행 유지 → 네 번째 429·DB 미진입 → 한 실행 종료 후 재요청 201 → 쿠폰 4·발급 수 4·활성 token 0) |
| `CouponRedisFailureTest` | Redis 컨테이너를 멈춰도 신규 발급 201 계속, 기존 목록 유지, breaker가 열린 뒤 Redis 호출 건너뜀, 직행 상한(C)에서 429 |
| `CouponRedisResumeTest` | PONG만 되고 쓰기가 막힌 Redis와 상태 유실(FLUSHALL)에서의 재개, 유효 용량 차감, 기동 실패 후 자동 재개, 수동 모드 승인 |
| `CouponRateLimitV1ApiE2ETest` | `RATE_LIMITED` 429와 Retry-After, `NO_PERMIT`과 다른 오류 코드 |
| `CouponListV1ApiE2ETest` | 본인 목록만, 정렬 |

## 9. 남은 일

- 기술 글은 쓰지 않았다.
- 다중 인스턴스, 사용자별 Rate limiter, Retry-After의 서버 강제는 범위 밖이다.
