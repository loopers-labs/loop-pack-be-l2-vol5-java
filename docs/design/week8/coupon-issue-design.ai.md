# 선착순 쿠폰 발급 상세 (W8, AI용)

사람용 [coupon-issue-design.md](coupon-issue-design.md)의 구현 상세다. 코드를 보면 알 수 있는 내용은 적지 않는다. 요구사항은 `docs/note/week8/실습 과제.md`, 개념은 `docs/note/week8/발제.md`를 따른다.

## 1. 역할 분리

| 저장소 | 원본인 것 | 원본이 아닌 것 |
|---|---|---|
| Redis | 당첨 순번(winners), 약속 전 당첨(PENDING), 현재 DB 실행(running) | 발급 완료, 약속한 당첨 |
| MySQL | 발급 완료 쿠폰, 약속한 당첨(ticket), issued_count, 캠페인 정의 | 당첨 순서 |

- 발급 완료 = DB에 쿠폰 행이 commit된 상태 (과제 정의).
- 당첨 = winners에 userId가 기록된 상태.
- **약속 원칙**: 201·202로 응답하기 전에 그 결과(쿠폰 또는 ticket)가 DB에 있어야 한다. Redis에만 있는 PENDING은 약속하지 않은 당첨이며, Redis 유실·재구성 때 사라질 수 있다.

## 2. Redis 키

캠페인마다 세 키. hash tag `{campaignId}`로 같은 슬롯에 둔다. 캐시 키와 섞이지 않게 접두사를 전용으로 쓴다.

| 키 | 타입 | 필드·값 |
|---|---|---|
| `coupon-issue:{campaignId}:meta` | Hash | `active`(1/0), `open`(1/0), `quota`(정수) |
| `coupon-issue:{campaignId}:winners` | Hash | userId → `PENDING`, `TICKETED` 또는 couponId(10진 문자열) |
| `coupon-issue:{campaignId}:running` | Set | 실행 token (서버가 실행마다 UUID 생성) |

- 연결: `@Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String>`. 기본 `REPLICA_PREFERRED` 템플릿으로 수용 판단을 읽지 않는다.
- serializer는 String. userId·couponId·quota는 10진 문자열로 넘긴다.
- 수용량 C는 서버 설정(`coupon.issue.capacity`, 기본 3). HTTP 입력으로 받지 않는다.
- token은 userId·couponId·요청 키와 무관한 UUID. 한 HTTP 실행에서만 쓴다. 같은 실행의 자리 재시도는 같은 token을 쓴다.
- 자리 대기: `coupon.issue.wait-timeout`(기본 2s), 재시도 간격 10~50ms + jitter. 서버 설정이며 HTTP 입력으로 받지 않는다.
  - C와 대기 시간은 사람용 문서의 공식으로 정한다: `X = min(C/T_tx, 1/T_lock)`, `C = min(⌈T_tx/T_lock⌉, ⌊P×B⌋, W)`, `대기 = clamp(k×(min(W,Th)−C)/X, T_tx_p99, 클라이언트 timeout − 여유)`.
  - T_tx는 `CouponIssueWriter`의 Timer `coupon.issue.write`(actuator prometheus 히스토그램)로 잰다. T_lock은 따로 재지 않고, C를 키워도 늘지 않는 포화 처리량의 역수로 추정한다(같은 캠페인 행 UPDATE→commit이 직렬).
  - 실측(k6 `k6/run-coupon-rush.sh`, 한도 100·1,000명): C=3·5·10에서 처리량 약 170·160·97/s, T_lock ≈ 6ms → C=3 유지, 계산 대기 ≈ 1.1s(설정 2s는 여유).

## 3. Lua 계약

스크립트는 `apps/commerce-api/src/main/resources/redis/`에 두고 `DefaultRedisScript<String>`으로 실행한다. 결과는 문자열 하나라서 unchecked cast가 없다.

### reserve.lua

`KEYS = [meta, winners, running]`, `ARGV = [userId, token, capacity]`

```lua
local capacity = tonumber(ARGV[3])
if not capacity or capacity < 1 or capacity % 1 ~= 0
    or ARGV[1] == '' or ARGV[2] == '' then
  return redis.error_reply('invalid admission input')
end
local meta = redis.call('HMGET', KEYS[1], 'open', 'active', 'quota')
if meta[1] ~= '1' then return 'CLOSED' end
local current = redis.call('HGET', KEYS[2], ARGV[1])
if current == 'PENDING' or current == 'TICKETED' then return current end
if current then return 'ISSUED:' .. current end
if meta[2] ~= '1' then return 'INACTIVE' end
local quota = tonumber(meta[3])
if not quota then return redis.error_reply('invalid quota') end
if redis.call('HLEN', KEYS[2]) >= quota then return 'SOLD_OUT' end
redis.call('HSET', KEYS[2], ARGV[1], 'PENDING')
if redis.call('SCARD', KEYS[3]) >= capacity then return 'WON_WAITING' end
redis.call('SADD', KEYS[3], ARGV[2])
return 'WON_RUNNING'
```

검사 순서의 이유:
1. `open` 먼저: 닫힌 상태의 winners는 믿을 수 없다. meta 키가 없으면(유실) `open`이 nil이라 CLOSED.
2. winners를 `active`보다 먼저: 종료된 캠페인이어도 이미 받은 사람에게는 같은 결과를 준다.
3. HLEN은 PENDING·TICKETED·couponId를 모두 센다 = 당첨자 수.
4. 당첨 기록(HSET) 뒤에 자리 확인: 자리가 없어도 순번은 확정된다.

### acquire.lua

`WON_WAITING`을 받은 실행과 마감 보정이 자리만 다시 요청할 때 쓴다. `KEYS = [meta, winners, running]`, `ARGV = [userId, token, capacity]`

```lua
local capacity = tonumber(ARGV[3])
if not capacity or capacity < 1 or capacity % 1 ~= 0
    or ARGV[1] == '' or ARGV[2] == '' then
  return redis.error_reply('invalid admission input')
end
if redis.call('HGET', KEYS[1], 'open') ~= '1' then return 'CLOSED' end
local current = redis.call('HGET', KEYS[2], ARGV[1])
if current ~= 'PENDING' and current ~= 'TICKETED' then return 'NOT_PENDING' end
if redis.call('SISMEMBER', KEYS[3], ARGV[2]) == 1 then return 'ACQUIRED' end
if redis.call('SCARD', KEYS[3]) >= capacity then return 'FULL' end
redis.call('SADD', KEYS[3], ARGV[2])
return 'ACQUIRED'
```

- `SISMEMBER` 재확인: 앞선 시도의 응답이 timeout으로 불확실했을 때, 같은 실행 안에서만 자기 자리를 다시 확인한다. 다른 실행에 token을 넘기지 않는다.
- `NOT_PENDING`: 그사이 couponId가 기록됐다(마감 보정 등). 대기를 멈추고 winners 값으로 응답한다.
- 당첨 판정은 하지 않는다. 대기자는 이미 당첨된 사람(PENDING), 마감 보정 대상은 TICKETED다.

### ticket.lua

DB에 ticket을 insert한 뒤 부른다. `KEYS = [winners]`, `ARGV = [userId]`

```lua
if redis.call('HGET', KEYS[1], ARGV[1]) == 'PENDING' then
  redis.call('HSET', KEYS[1], ARGV[1], 'TICKETED')
end
return 'OK'
```

- PENDING일 때만 바꾼다. 이미 couponId면 그대로 둔다.
- 이 호출이 실패해도 ticket은 DB에 있으므로 202 약속은 유효하다. 로컬 수용 스위치만 닫고, 재구성 때 DB에서 TICKETED로 되살린다.

### finish.lua

`KEYS = [winners, running]`, `ARGV = [userId, token, couponId 또는 '']`

```lua
redis.call('SREM', KEYS[2], ARGV[2])
local current = redis.call('HGET', KEYS[1], ARGV[1])
if ARGV[3] ~= '' and (current == 'PENDING' or current == 'TICKETED') then
  redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
end
return 'OK'
```

- 자기 token만 SREM. 두 번 호출하거나 모르는 token이어도 다른 token은 남는다.
- couponId 기록은 PENDING·TICKETED일 때만. 이미 couponId면 덮어쓰지 않는다.
- issued_count·쿠폰은 건드리지 않는다.

## 4. 결과 ↔ 서버 동작

| Lua 결과 | 서버 동작 | 응답 |
|---|---|---|
| `CLOSED` | DB에서 (campaignId, userId) 쿠폰 조회. 캠페인 자체가 없으면 404 | 있으면 200, 없으면 503 `COUPON_ADMISSION_UNAVAILABLE` |
| `ISSUED:{id}` | DB 미진입 | 200 couponId |
| `TICKETED` | DB 미진입 | 202 status=PENDING |
| `PENDING` | DB 미진입 | 409 `COUPON_ISSUE_IN_PROGRESS` |
| `INACTIVE` | DB 미진입 | 409 `COUPON_CAMPAIGN_UNAVAILABLE` |
| `SOLD_OUT` | DB 미진입 | 409 `COUPON_SOLD_OUT` |
| `WON_WAITING` | wait-timeout까지 acquire 반복. `ACQUIRED`면 5장 DB TX → finish. `FULL`이 이어져 시간 초과되거나 `CLOSED`면 **약속 기록**(아래) | 201 등(5장) 또는 202 / 500 |
| `WON_RUNNING` | 5장 DB TX → finish | 201 / 200 / 202 / 500 (7장) |
| Redis 예외·timeout·알 수 없는 결과 | 로컬 수용 스위치 닫기 → `CLOSED`와 같이 DB 조회 | 200 또는 503 |

**약속 기록** (쿠폰을 저장하지 못한 당첨자에게 202를 주기 전):
1. `coupon_issue_ticket` insert (짧은 별도 TX). 유일 제약 충돌은 이미 있는 것이므로 성공으로 본다.
2. `ticket.lua`로 winners를 TICKETED로 바꾼다. 실패해도 1이 성공했으면 202 (로컬 스위치만 닫음).
3. 1이 실패하면 약속하지 않는다 → 500 `INTERNAL_ERROR`, winners는 PENDING 그대로.

- ErrorType 추가: `COUPON_SOLD_OUT`·`COUPON_CAMPAIGN_UNAVAILABLE`·`COUPON_ISSUE_IN_PROGRESS` → 409, `COUPON_ADMISSION_UNAVAILABLE` → 503. `ErrorTypeHttpStatusMapper`에 매핑 추가.
- 202·201·200은 성공 응답이다. 응답 DTO에 `status`(ISSUED/PENDING)와 couponId(있을 때)를 담는다.
- PENDING 재요청이 409인 이유: PENDING은 약속하지 않은 상태라 202를 줄 수 없다. 처리 중인 같은 사용자의 다른 요청이 결과를 정한다. 기다리게 하지 않는 이유: 같은 사용자의 반복 요청이 스레드를 쥐지 않게 Redis에서 바로 끝낸다.
- ErrorType 메시지:

| ErrorType | 메시지 |
|---|---|
| `COUPON_ISSUE_IN_PROGRESS` | 이미 당첨되어 발급을 처리하고 있습니다. 잠시 후 다시 확인해 주세요. |
| `COUPON_SOLD_OUT` | 쿠폰이 모두 소진되었습니다. |
| `COUPON_CAMPAIGN_UNAVAILABLE` | 종료되었거나 진행 중이 아닌 쿠폰 행사입니다. |
| `COUPON_ADMISSION_UNAVAILABLE` | 지금은 쿠폰 발급을 받을 수 없습니다. 잠시 후 다시 시도해 주세요. |

- 409·503 문구는 발급 성공이나 나중 발급 보장으로 읽히지 않게 쓴다(과제 1장).
- 수용 응답이 불확실하면(예외·timeout) DB 발급을 시작하지 않는다. 이때 Lua가 실제로는 PENDING·token을 남겼을 수 있다 → 재개 절차(8장)에서 정리.

## 5. DB

| 테이블 | 컬럼 | 제약 |
|---|---|---|
| coupon_campaign | id, name, discount_amount, total_quota, issued_count, active | `issued_count <= total_quota` |
| coupon | id(=couponId, IDENTITY), campaign_id, user_id, discount_amount(혜택 snapshot), issued_at | UNIQUE(campaign_id, user_id), INDEX(user_id, issued_at DESC, id DESC) |
| coupon_issue_ticket | id, campaign_id, user_id, won_at | UNIQUE(campaign_id, user_id) |

- ticket은 쿠폰이 저장된 뒤에도 지우지 않는다. 재구성은 쿠폰을 먼저 보고, 쿠폰이 없는 ticket만 TICKETED로 되살린다.
- ticket insert는 세마포어 밖의 DB 쓰기다. 시간 초과·DB 실패 경로에서만 생기며, 그 수는 당첨자 수를 넘지 않는다.

발급 TX (`WON_RUNNING`일 때만, `TransactionTemplate`으로 Redis 호출 밖에서 짧게):
1. coupon insert (혜택은 캠페인에서 복사).
2. `UPDATE coupon_campaign SET issued_count = issued_count + 1 WHERE id = ? AND issued_count < total_quota`.
3. 2가 0행이면 정합성 오류로 예외 → 전체 rollback.

- insert를 먼저 하는 이유: 같은 사용자 쿠폰이 이미 있으면 유일 제약 충돌이 먼저 드러난다. UPDATE를 먼저 하면 마지막 1장 상황에서 이미 받은 사용자를 품절로 잘못 판정한다.
- DB는 `active`를 보지 않는다. 당첨 = 발급 확정.
- 목록 `GET /api/v1/coupons`: `WHERE user_id = :requester ORDER BY issued_at DESC, id DESC`. Redis를 쓰지 않는다.

## 6. 상태 전이 (winners 값)

| 현재 | 사건 | 다음 | 주체 |
|---|---|---|---|
| (없음) | reserve 당첨 | PENDING | reserve.lua |
| PENDING | DB commit 뒤 finish | couponId | finish.lua |
| PENDING | 대기 시간 초과·DB 실패 뒤 ticket insert 성공 | TICKETED | ticket.lua |
| PENDING | ticket insert 실패 | PENDING (약속 없음, 500) | — |
| PENDING·TICKETED | 마감 보정 | couponId | 관리자 보정 → finish.lua |
| PENDING | Redis 유실 뒤 재구성 | (없음) | 재구성 (DB에 없으므로) |
| TICKETED | Redis 유실 뒤 재구성 | TICKETED | 재구성 (DB ticket에서) |
| couponId | 어떤 사건이든 | couponId | 변경 없음 |

## 7. DB TX 결과별 처리

| TX 결과 | finish 인자 | 응답 |
|---|---|---|
| commit | couponId | 201 |
| 유일 제약 충돌 (Redis 재구성 불일치 때만) | rollback 뒤 새 TX로 기존 쿠폰 조회 → couponId | 200 |
| UPDATE 0행 (정합성 오류) | '' | 로컬 수용 닫기, 500. 관리자 재구성 대상 |
| 연결 획득 timeout·연결 끊김·잠금 timeout | '' | 4장 약속 기록 → 202, 기록 실패 시 500 |
| 그 밖의 예외 | '' | 500 |

- 유일 제약 충돌만 중복으로 본다. 연결 오류·다른 제약 오류를 중복 성공으로 바꾸지 않는다.
- finish는 `finally`에서 TX 종료(commit/rollback) 확인 뒤 부른다. HTTP 연결 종료·client timeout만으로 부르지 않는다.
- finish 실패: 쿠폰·issued_count는 그대로. 로컬 수용 스위치를 닫는다. 응답은 TX 결과대로.

## 8. 수용 스위치와 재개

과제의 "기본 닫힘·명시적 열기·건강 확인만으로 열지 않음"을 지키려면 Redis `open`만으로는 부족하다. Redis가 데이터를 유지한 채 재시작하면 `open=1`이 그대로 살아나기 때문이다.

| 장치 | 위치 | 닫히는 때 | 열리는 때 |
|---|---|---|---|
| 로컬 수용 스위치 | 애플리케이션 메모리 | 기동 시(기본 닫힘), Redis 예외·timeout, finish 실패, 정합성 오류 | 관리자 열기 API만 |
| Redis `meta.open` | Redis | 관리자 닫기, 키 유실(nil) | 관리자 열기 API만 |

- 신규 발급은 둘 다 열려 있을 때만 Lua를 호출한다. 로컬 스위치가 닫혀 있으면 `CLOSED`와 같이 처리한다.

재개 절차 (관리자 열기 API):
1. 이 인스턴스의 진행 중 발급 실행이 0인지 확인한다. 0이 아니면 409 `CONFLICT`로 거절하고 닫힌 상태를 유지한다.
2. DB만으로 winners를 다시 만든다: 쿠폰이 있는 userId → couponId, 쿠폰 없는 ticket의 userId → TICKETED. Redis에만 있던 PENDING은 지운다(약속하지 않은 당첨). 그 자리는 남은 수량으로 돌아간다.
3. running에서 이 인스턴스가 만든 token만 SREM (애플리케이션이 기록해 둔 token 목록 기준).
4. meta `quota`·`active`를 DB 값으로 맞추고 `open=1`, 로컬 스위치 열기.

- 진행 중 실행 수와 자기 token은 `CouponIssueExecutions`(메모리)가 센다. token은 Lua 호출 **전에** 기록하고, Redis에 들어가지 않은 것이 확실하거나(자리 없음·판정 결과) finish가 성공했을 때만 지운다. 응답이 불확실한 token은 남겨 두었다가 3단계에서 지운다.
- 다른 인스턴스의 실행·token은 알지 못한다. 단일 로컬 인스턴스의 통제된 재개만 보장한다.
- token TTL을 두지 않는다. 시간 경과로 자리가 반환되지 않는다.
- 종료가 불명확하면 닫힌 상태를 유지한다.

## 9. 관리자 기능 (`/api-admin/v1/coupon-campaigns`)

| API | 기능 | 동작 |
|---|---|---|
| `POST /` | 준비 | DB 캠페인 생성 + Redis meta(active=1, open=0, quota). 열지 않는다 |
| `POST /{id}/open` | 열기 | 8장 재개 절차. 처음 열 때와 장애 뒤 재개가 같은 절차다 |
| `POST /{id}/close` | 종료 | Redis `active=0` 먼저, 그다음 DB active=false. Redis 실패면 DB를 바꾸지 않고 503 |
| `POST /{id}/reconcile` | 마감 보정 | winners를 HSCAN해 PENDING·TICKETED만: DB에 쿠폰이 있으면 couponId만 기록(`recordIssued`, 자리 없음), 없으면 `acquire.lua`로 자리를 받아 발급 TX → finish. `FULL`이면 멈추고 남은 수를 돌려준다. 응답 `{issued, recorded, remaining}` |

- 관리 작업 중 Redis를 바꾸지 못하면 로컬 스위치를 닫고 503으로 알린다. 관리자는 확인 뒤 다시 시도한다.

## 10. 테스트 시나리오

실제 MySQL·Redis Testcontainers를 쓰고, Redis는 만든 캠페인 키만 정리한다.

| 테스트 클래스 | 다루는 장면 |
|---|---|
| `CouponCampaignTest` | 한도 1 미만 캠페인 거절 |
| `CouponIssueWriterTest` | DB 발급 TX: 저장·rollback·중복·8명이 5장 경쟁 (Redis 없이, DB handler 직접 동시 호출) |
| `CouponIssueRedisAdmissionTest` | Lua 판정·자리·결과 기록·자기 token 반환·TTL 없음 |
| `CouponIssueFacadeTest` | 정상·재요청·같은 사용자 동시 요청·DB 실패 시 202·품절 |
| `CouponApiE2ETest` | 201·본인 목록만 노출·409 품절·헤더 누락 400 |
| `AdminCouponCampaignFacadeTest` | 재구성·종료·마감 보정 |
| `CouponAdmissionTest` | C=3 수용 실험·대기 시간 초과 |
| `CouponRedisFailureTest` | 실제 Redis pause·PONG 뒤에도 닫힘·반환 실패 복구·202 뒤 상태 유실 |

| 과제 장면 | 기대 |
|---|---|
| 정상 발급·본인 목록 | 201, 쿠폰 1·issued_count 1, 본인 목록에 같은 ID, 다른 사용자 목록에 없음 |
| 같은 사용자 재요청 | 200 같은 couponId, DB 조회·쓰기 0, issued_count 그대로 |
| 같은 사용자 동시 요청 | 쿠폰 1, 나머지 응답은 409 처리 중 또는 200 |
| 마지막 수량 경쟁 (quota 5, 8명, DB handler 직접) | 발급 5·한도 소진 3, 쿠폰 5·issued_count 5. Redis 판정 단계는 별도로 quota 5에 20명 → 당첨 5·품절 15 |
| insert 실패 | 쿠폰·issued_count rollback, token 반환, ticket 기록 후 202·TICKETED |
| commit 뒤 응답 유실 | 재요청 200 같은 couponId, 추가 발급 0 |
| C=3 수용 실험 | barrier로 3 실행 유지(running 3) → 4번째 요청은 자리를 기다리며 DB 미진입·쿠폰 없음 → 하나를 진행시켜 DB 발급·종료·token 반환 확인 → 4번째가 그 자리를 얻어 201·쿠폰 생성 → 나머지 종료 후 쿠폰 4·issued_count 4·running 0. 과제의 "4번째 429"와 달리 당첨자는 거절하지 않고 기다린다 |
| 자리 대기 시간 초과 | running이 C개로 유지된 채 wait-timeout이 지나면 202, ticket 1, winners는 TICKETED, running 증가 없음, 쿠폰 없음 |
| 202 뒤 Redis 유실 | Redis 키 삭제 → 재구성 → TICKETED 복원, 약속하지 않은 PENDING은 사라짐 → 마감 보정에서 TICKETED가 쿠폰으로 발급 |
| token 반환 | 같은 token 두 번·모르는 token 반환에도 다른 token 유지 |
| Redis 중단 (실제 endpoint) | 신규 503·DB 신규 효과 0, 기존 보유자 POST 200·목록 200, 복구 후 PONG만으로 열리지 않음 |
| 반환 실패·복구 | 쿠폰 유지, 수용 닫힘, 재개 절차 뒤 자기 token만 정리하고 열림 |
| TTL | 시간이 지나도 running token이 사라지지 않음 |

- barrier는 Redis 획득 직후·DB TX 전에 둔다. deadline을 두고 실패 시 자기가 시작한 실행을 풀고 정리한다.
- 테스트 0건·skip·연결 실패는 성공이 아니다.
