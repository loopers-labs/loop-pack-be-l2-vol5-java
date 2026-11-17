# 종료된 발급 작업자의 토큰 한 개 복구

MySQL에 `202 WAITING` 예약이 있고, 기존 작업자 JVM은 종료됐지만 같은 Redis master에 그 작업자의 발급 토큰이 남았을 때 사용한다. 운영자가 종료와 DB 결과를 먼저 확인하고 Redis 점유를 정리한 뒤, 지정한 작업자 한 대를 명시적으로 재개한다. 고객은 다시 신청할 필요가 없다.

확인용 기동과 최종 재개 모두 **같은 MySQL 스키마와 같은 Redis master·DB**를 사용한다. 이 저장소의 local·test 프로필은 기동 시 테이블을 생성하므로 기존 데이터로 기동할 때마다 `spring.jpa.hibernate.ddl-auto=validate`를 명시한다. [기존 예약을 유지하는 스키마 설정](../week2/commerce-schema-operations.md)

### 1. 이전 실행과 토큰을 식별한다

1. 발급 작업을 일시 중단한다. 이전 프로세스가 실행됐던 호스트 또는 컨테이너의 식별자와 시작 시각으로 대상을 특정한다. 프로세스 관리 도구에서 **해당 실행의 종료**를 확인하고 재시작 정책이나 다른 인스턴스가 발급을 다시 시작하지 않도록 한다. 같은 JVM이 살아 있으면 해당 JVM의 기존 재개 절차를 사용한다.
2. 종료된 **같은 실행**의 로그에서 `Opened coupon issuance semaphore`의 `owner`와 `epoch`, `Acquired coupon issuance`의 `token`, `campaign`, `reservation`, `user`를 기록한다. 이전 배포 로그가 없는 경우에도 같은 실행과 토큰의 연결을 독립적으로 입증해야 한다. 실행이나 소유자가 불분명하면 닫힘을 유지한다.
3. 전용 Redis master에서 상태 해시의 `epoch`, `capacity`, `owner:<token>`과 점유 Set의 해당 멤버를 읽어 로그 및 배포 설정과 대조한다. 운영자 입력의 출처는 이전 작업자 로그다. 쿠폰 ID나 사용자 ID를 실행 토큰 대신 사용하면 안 된다.

### 2. MySQL에서 발급 트랜잭션의 결과를 확인한다

같은 캠페인 행을 잠그므로 이전 발급 트랜잭션이 아직 이 행을 점유하고 있으면 `NOWAIT`가 실패한다. **쓰기 primary의 같은 연결**에서 로그의 숫자로 세 변수를 설정한 뒤 아래를 실행한다. 오류, 잠금 거절, 결과가 0행이거나 예상과 다르면 `ROLLBACK`하고 Redis 토큰을 보존한다.

```sql
-- @campaign_id, @reservation_id, @user_id를 종료된 작업자의 값으로 설정한 mysql 세션
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;
SELECT id FROM coupon_campaign WHERE id = @campaign_id FOR UPDATE NOWAIT;
-- 저장소 루트에서 시작한 mysql 클라이언트
SOURCE apps/commerce-api/src/main/resources/db/operations/verify-coupon-issuance-settlement.sql
```

캠페인 행이 하나이며 결과가 아래 두 경우 중 하나인지 확인하고 **`COMMIT` 성공 응답**까지 확인한다. DB 연결을 잃으면 처음부터 다시 대조한다. 이 단계는 예약과 보유 쿠폰을 읽는다. [결과 확인 SQL](../../apps/commerce-api/src/main/resources/db/operations/verify-coupon-issuance-settlement.sql)

| 예약 결과 | 보유 행 | 재개 후 동작 |
| --- | --- | --- |
| `WAITING` | 0장, `coupon_id`가 NULL | 지정한 작업자가 원래 예약을 찾아 한 장을 발급한다. |
| `ISSUED` | 1장, 기존 `coupon_id`가 있음 | 원래 쿠폰을 유지한다. 해당 예약은 작업자의 대기 조회에서 제외된다. |

### 3. 대조한 토큰 하나를 정리하고 발급을 명시적으로 재개한다

저장소 루트에서 아래 명령을 실행한다. `REDIS_MASTER_HOST`, `REDIS_MASTER_PORT`, `REDIS_DB`는 장애가 난 발급 앱과 같은 master·DB를 지정한다. `EPOCH`, `OWNER`, `TOKEN`은 앞서 대조한 값, `CAPACITY`는 그 발급 그룹의 기존 설정값이다. 운영 환경에서 사용하는 인증·TLS 설정을 적용한다.

```sh
redis-cli -h "${REDIS_MASTER_HOST:?}" -p "${REDIS_MASTER_PORT:?}" -n "${REDIS_DB:?}" --raw \
  --eval apps/commerce-api/src/main/resources/redis/coupon-issue-semaphore.lua \
  'commerce:coupon-issue:v1:{global}:state' 'commerce:coupon-issue:v1:{global}:tokens' \
  , recover-one "${EPOCH:?}" "${CAPACITY:?}" "${OWNER:?}" "${TOKEN:?}"
```

성공 응답은 `1`이다. Lua가 세대·용량·소유자·토큰과 점유 수를 대조한 후 지정한 Set 멤버와 해당 소유자 필드를 함께 제거하고 `held`를 1 줄인다. 다른 멤버는 보존한다. 빈 응답·오류·통신 두절이면 닫힘을 유지하고 같은 master에서 멤버·소유자·세대·점유 수를 다시 확인한다. 응답만 잃어 이미 정리된 경우도 있으므로 다른 토큰을 대신 지우지 않는다. [운영 명령의 Lua 구현](../../apps/commerce-api/src/main/resources/redis/coupon-issue-semaphore.lua)

정리만으로 닫힌 JVM의 발급은 시작되지 않는다. 이번 단일 작업자 구성에서 잔류 토큰이 0개이고 `held=0`이며 해당 소유자 필드가 사라진 것을 확인한다. 다른 토큰이 남으면 그 실행의 종료를 따로 확인한다. 지정한 작업자를 기존 `commerce.coupon.worker.enabled=true`, 캠페인 목록과 용량으로 **명시적으로 기동**한다. 같은 예약 ID의 상태와 본인 목록의 쿠폰 ID를 조회하고, 발급 종료 후 최종 점유 0개를 확인한다. [같은 Redis를 유지하는 통합 테스트](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponProcessLossRecoveryHttpIntegrationTest.java)
