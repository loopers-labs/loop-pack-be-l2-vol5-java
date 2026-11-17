# 쿠폰 API와 인스턴스 운영

고객·관리자 HTTP와 독립 JVM 두 개의 대상 검증을 통과한 C06 소스 기준 안내다.
전체 검사 실패 모듈의 환경 재실행을 포함한 최종 확인 범위는 [검증 근거](README.md#전체-검사-결과의-범위)를 따른다.
관리자 요청은 대상 인스턴스 주소에 직접 보낸다. 로드밸런서를 통해 서로 다른 인스턴스의 상태와 명령을 섞지 않는다.

주 HTTP 포트와 `management.server.port`는 별도 설정이다. 공통 monitoring 설정은 관리 포트 8081을 사용하므로
다른 앱·기존 PG와 겹치면 운영자가 각 인스턴스의 관리 포트를 별도로 지정해야 한다. 다른 서비스를 종료하는 절차가 아니다.
독립 프로세스 시험은 자식 앱의 관리 포트만 -1로 비활성화하고 주 서버는 임의 포트로 검증했다.

## 로컬 데이터 준비

기존 프로젝트 스키마와 쿠폰 DDL `docker/payment-lab/012-coupon-campaigns-slots.sql`, `013-coupons.sql`이 준비된 DB를 사용한다.
여기서는 기존 DB를 초기화하거나 테이블을 삭제하는 명령을 제공하지 않는다.

`local` 프로필의 JPA 기본값은 `ddl-auto=create`다. 재시작 시 기존 데이터를 보존해야 하는 아래 실행에서는
`--spring.jpa.hibernate.ddl-auto=validate`를 명시한다. 필요한 테이블이 없으면 시작이 거절되며 스키마 준비 상태를 먼저 확인한다.

```powershell
# 저장소 루트에서 실행. 이미 설치된 DB·Redis 등 프로젝트 의존 서비스가 필요하다.
.\gradlew.bat :apps:commerce-api:bootRun --args="--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=validate"
```

`LocalUserFixtureInitializer`는 local 프로필에서 사용자 1·2와 지갑이 없으면 생성한다.
쿠폰 관리자는 resolver가 존재를 확인한 사용자 ID 1만 허용한다. 사용자 ID를 헤더로 전달하는 학습용 규칙이며 실제 인증은 아니다.

새 캠페인 생성은 공개 CRUD가 아니라 다음 명시적 로컬 fixture만 제공한다.

```powershell
.\gradlew.bat :apps:commerce-api:bootRun --args="--spring.profiles.active=local,coupon-fixture --spring.jpa.hibernate.ddl-auto=validate --coupon.fixture.enabled=true --coupon.fixture.create=true --coupon.fixture.activate=true"
```

이 실행은 총량 3, 1,000원 할인, 기간 `2026-11-01T00:00:00Z` 이상 `2030-01-01T00:00:00Z` 미만의 캠페인을 새로 만든다.
로그의 준비 TX 완료 결과에서 실제 `campaignId`를 확인한다. `create=true`로 다시 시작하면 새 캠페인이 생성되므로 평소 재시작에서는
fixture 프로필·옵션을 제거한다. 기존 캠페인 재준비는 `create` 대신 `--coupon.fixture.campaign-id=<기존 ID>`를 명시하는 코드 경로다.
재준비는 발급 슬롯·쿠폰·발급 수를 초기화하지 않는다. 캠페인 활성화와 인스턴스 수용 개방은 별개다.

### 준비 결과 ID를 잃었을 때

준비 TX가 성공했지만 활성화가 실패했거나 로그 전에 중단됐다면 새 생성 요청을 반복하지 않는다.
같은 DB에서 이름·기간·생성 시각·슬롯 수를 읽고 실제 실행 시각과 대조한다. 후보가 여럿이면 가장 큰 ID를 자동 선택하지 않는다.

```sql
SELECT c.id, c.name, c.active, c.total_quota, c.issued_count,
       c.starts_at, c.ends_at, c.created_at,
       (SELECT COUNT(*) FROM coupon_slots s WHERE s.campaign_id = c.id) AS prepared_slots
FROM coupon_campaigns c
WHERE c.name = '로컬 쿠폰 캠페인'
ORDER BY c.created_at DESC, c.id DESC;
```

확인한 ID로 `Prepare`를 재실행하면 기존 슬롯·상태·발급 수를 보존한다. 일부 슬롯만 있으면 자동 보충하지 않는다.
fixture 생성/준비와 활성화는 별도 TX이므로 활성화 실패가 이미 저장된 비활성 캠페인을 취소하지 않는다.

## 고객 API

| 요청 | 성공 결과 |
|---|---|
| `POST /api/v1/coupon-campaigns/{campaignId}/issues` | 최초 201, 기존 결과 200·동일 couponId |
| `GET /api/v1/coupons` | 본인 전체 목록, `issuedAt DESC, couponId DESC`, 없으면 빈 배열 |

발급 body는 필요 없다. 사용자 ID는 기존 `X-USER-ID` resolver가 확인한 값만 사용한다.
성공 data는 `couponId`, `campaignId`, `issuedAt`, `benefitDescription`, `discountAmount` 5개 필드다.
발급 당시 저장한 혜택을 반환하며 `userId`, `slotId`, 실행 ID, DB 연결 정보는 고객 응답에 포함하지 않는다.

```powershell
$base = 'http://127.0.0.1:8080'
$customer = @{ 'X-USER-ID' = '2' }
$admin = @{ 'X-USER-ID' = '1' }
$campaignId = 1 # 실제 fixture 로그의 ID로 변경

Invoke-RestMethod -Method Post -Uri "$base/api/v1/coupon-campaigns/$campaignId/issues" -Headers $customer
Invoke-RestMethod -Method Get -Uri "$base/api/v1/coupons" -Headers $customer
```

기본 닫힘에서는 신규 발급이 503이다. 이미 발급된 쿠폰은 닫힘·품절·비활성·기간 종료 뒤에도 그대로 반환한다.

## 상태 확인과 개방

`coupon.admission.max-concurrency`는 인스턴스 시작 시 한 번 읽는다. 초기값은 3이며 0·음수·잘못된 형식은 시작을 거절한다.
실행 중 최대값 변경 API는 없다. 상태 data의 `instanceId`, `bootId`, `capacity`, `available`, `running`, `held`,
`restartVerified`, `open`, `reason`, `executions`를 확인한다. `running`과 종료 불명으로 보류된 `held`는 다른 수치다.

```powershell
$state = (Invoke-RestMethod -Method Get -Uri "$base/api/v1/admin/coupon-admission" -Headers $admin).data
$bootId = $state.bootId
$state
```

실행 이력이 없는 **최초 배포임을 운영자가 확인한 경우에만** 다음 요청을 사용한다. 빈 registry 자체가 최초 배포의 증거는 아니다.
`auditReference`는 실제 확인 기록의 식별자로 바꾼다. 서버는 입력 외에도 현재 DB의 작업 정지와 쿠폰·슬롯·발급 수 정합성을 확인한다.

```powershell
$verify = @{
    expectedBootId = $bootId
    previousProcessStopped = $true
    auditReference = '운영 확인 기록 식별자'
    initialDeployment = $true
    previousSessions = @()
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/coupon-admission/verify-restart" -Headers $admin -ContentType 'application/json' -Body $verify

$open = @{ expectedBootId = $bootId } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/coupon-admission/open" -Headers $admin -ContentType 'application/json' -Body $open
```

일반 재시작은 `initialDeployment=false`와 이전 boot 로그에서 확인한 비어 있지 않은 세션 목록을 사용한다.
각 항목은 `{ "serverId": "실제 DB server_uuid", "connectionId": 실제 양의 정수 }`다.
이전 프로세스 종료와 목록의 완전성은 운영 확인 책임이다. 서버는 같은 DB인지와 각 이전 세션 소멸·TX 부재를 다시 확인한다.
확인 실패 시 닫힘을 유지하며 과거의 검증 성공을 재사용하지 않는다. 검증 성공도 자동으로 수용을 열지 않는다.
이전 boot에서 쿠폰 TX가 한 번도 실행되지 않아 `TX_STARTED` 로그가 없더라도 최초 배포로 바꾸어 우회하지 않는다.
일반 재시작의 이전 세션 목록을 확보하지 못했다면 닫힘을 유지하고, 정상 종료 전 DB 세션 목록을 기록하는 등 운영 확인 경로를 먼저 준비한다.

## 닫기와 지정 실행 복구

```powershell
$close = @{ reason = '실행 종료 확인 및 점검' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/coupon-admission/close" -Headers $admin -ContentType 'application/json' -Body $close
```

close는 신규 획득만 막는다. 진행 중인 DB 작업이나 HTTP 요청을 취소하지 않는다.
로그의 `instance`, `boot`, `execution`, `campaign`, `user`, `TX_STARTED`, `TX_COMPLETED`, `ENDED`를 연결해 확인한다.
DB 작업 식별은 실제 TX 연결에서 읽은 `@@server_uuid`와 `CONNECTION_ID()`다. 인증정보를 로그나 고객 응답으로 전달하지 않는다.

확인 SQL은 같은 쓰기 DB의 권한 있는 검사 연결에서 수행한다. 다음은 읽기 전용 확인 예시다.

```sql
SELECT @@server_uuid, CONNECTION_ID();
SHOW GRANTS FOR CURRENT_USER;
SELECT ID, COMMAND, TIME, STATE
FROM information_schema.PROCESSLIST
WHERE ID = <로그에 기록된 원래 연결 ID>;
SELECT trx_mysql_thread_id, trx_state, trx_started
FROM information_schema.INNODB_TRX
WHERE trx_mysql_thread_id = <로그에 기록된 원래 연결 ID>;
```

원 세션이 살아 있으면 `Sleep`이어도 종료 증거로 인정하지 않는다. 검사 연결이 원 연결과 같아도 반환을 허용하지 않는다.
애플리케이션 실행 종료, 원 세션 소멸·TX 부재, 쿠폰↔ISSUED 슬롯 대응과 발급 수가 함께 확인되어야 한다.
풀 반환·쿠폰 존재·HTTP timeout·프로세스 종료 중 하나만으로 강제 반환하지 않는다.

```powershell
$recover = @{
    expectedBootId = $bootId
    executionIds = @('현재 boot의 실제 보류 실행 ID')
} | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/coupon-admission/recover" -Headers $admin -ContentType 'application/json' -Body $recover
```

복구 목록은 1~1000개다. 현재 boot의 지정 실행만 처리하고 각 ID에 `RELEASED`, `ALREADY_RELEASED`, `NOT_FOUND`,
`OTHER_BOOT`, `NOT_ENDED`, `RETURN_FAILED` 결과를 반환한다. 한 ID의 종료 근거 조회 실패는 그 ID를 보류하고 다른 ID를 계속 확인한다.
recover는 자동 open하지 않는다. 종료 확인되지 않은 실행이 남으면 open도 거절한다. 이전 boot의 ID로 새 Semaphore에 permit을 추가하지 않는다.

DB 종료 확인 adapter는 **직접 전역 PROCESS 권한**을 확인한다. 역할에 간접 위임된 권한을 추정하지 않는다.
실제 운영 `application` 계정의 권한 충족은 아직 검증하지 않았다. 테스트컨테이너의 관리자 계정 성공을 운영 권한 확인으로 대신하지 않는다.
권한이 부족하거나 DB 조회가 실패하면 닫힘·보류를 유지한다. 권한 변경은 운영 정책에 따라 별도로 판단한다.

## Redis 집계 대조와 재집계

```powershell
Invoke-RestMethod -Method Get -Uri "$base/api/v1/admin/coupon-campaigns/$campaignId/counter" -Headers $admin
Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/coupon-campaigns/$campaignId/counter/rebuild" -Headers $admin
```

대조 data는 `databaseCount`, `prepared`와 준비된 경우의 `aggregateCount`다. 미준비·키 유실은 `prepared=false`이며,
기존 전역 NON_NULL 설정에 따라 `aggregateCount` 필드가 JSON에서 생략된다. 실제 준비된 0건은 `prepared=true`, `aggregateCount=0`이다.
접근 실패는 빈 값이나 0으로 숨기지 않고 503으로 반환한다. 재집계 성공 data는 `aggregateCount`다.
대조는 DB와 Redis의 하나의 원자 snapshot이 아니므로 쓰기 중 순간 차이가 곧 데이터 손상을 뜻하지는 않는다.

재집계는 캠페인 행 잠금을 유지하고 DB의 확정 쿠폰 ID와 발급 수를 확인한 뒤 Redis 임시 집계를 원자 교체한다.
행 잠금 중 Redis 명령을 기다리므로 발급의 캠페인 잠금 1초 제한과 경합해 일시 거절이 생길 수 있다.
새 반영·새 재집계가 UUID fence를 바꾸면 지연된 옛 publish는 거절된다. 거절·결과 불명은 성공으로 간주하지 않고 대조 후 다시 확인한다.
활성 집계와 fence에는 TTL이 없고 임시 build만 60초 TTL을 사용한다.

Redis 반영 실패는 이미 확정된 쿠폰·DB 수량·고객 성공 응답을 바꾸지 않는다. `COUNTER_REBUILD_REQUIRED` 로그와 DB 대조로 누락을 확인한다.
commit 뒤 프로세스가 종료되면 메모리 호출 자체가 빠질 수 있다. 영속 전달 보장은 없으며 DB 기준 재집계가 복구 수단이다.

## HTTP 오류

공통 envelope는 `meta.result`, `meta.errorCode`, `meta.message`, `data` 구조를 유지한다.
기존 NON_NULL 설정으로 null 필드는 JSON에서 생략된다. 오류의 `data`와 성공의 null 오류 필드도 같은 규칙을 따른다.

| 상태 | 대표 쿠폰 코드·의미 |
|---|---|
| 400 | 잘못된 헤더·입력·경로 또는 `COUPON_INVALID_*` |
| 403 | `COUPON_ADMIN_FORBIDDEN` |
| 404 | `COUPON_CAMPAIGN_NOT_FOUND`; 존재하지 않는 사용자 식별은 기존 오류 규칙 |
| 409 | `COUPON_CAMPAIGN_NOT_STARTED`, `COUPON_CAMPAIGN_ENDED`, `COUPON_CAMPAIGN_INACTIVE`, `COUPON_SOLD_OUT`, `COUPON_RECOVERY_REJECTED` |
| 429 | `COUPON_CAPACITY_EXCEEDED`: 해당 인스턴스의 처리 자리 없음 |
| 503 | `COUPON_ADMISSION_UNAVAILABLE`, `COUPON_ISSUE_OUTCOME_UNKNOWN`, `COUPON_SLOT_BUSY`, `COUPON_LOCK_BUSY`, `COUPON_COUNTER_UNAVAILABLE` |

429·503은 발급 예약이나 나중의 성공을 보장하지 않는다. 기존 결과를 확인할 수 있으면 같은 couponId를 반환한다.

## 개발 환경과 제한 세션

확인한 개발 도구는 JDK 21.0.10, Gradle wrapper 8.13, Spring Boot 3.4.4다. 다른 PC는 실제 설치 경로에 맞춘다.
`docker/infra-compose.yml`의 기본 서비스는 MySQL 3306, Redis master 6379/replica 6380, Kafka host 19092다.
기존 PG·주문의 데이터 보존 실행은 [payment-lab 안내](../../../docker/payment-lab/README.md)를 따른다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.10'
& "$env:JAVA_HOME/bin/java.exe" -version
.\gradlew.bat --version
docker version
docker compose -f docker/infra-compose.yml ps
```

사용자 PowerShell에서 Docker가 동작해도 제한된 실행 세션의 pipe·소켓 권한을 증명하지는 않는다.
C01~C06 실제 검사는 필요한 명령을 사용자 PowerShell에서 실행하고 메인이 XML을 읽는 방식으로 확인했다.
기본 사용자 Gradle cache 쓰기만 막히는 경우에는 아래와 같이 프로젝트 내부 cache를 쓰는 대안이 있다.
이 방법은 Docker·소켓 권한을 해제하거나 데몬 접속 성공을 보장하지 않는다. 전역 ACL이나 기존 cache를 바꾸지 않는다.

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location).Path '.gradle/agent-user-home'
$env:GRADLE_RO_DEP_CACHE = Join-Path $env:USERPROFILE '.gradle/caches'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME | Out-Null
$gradleLauncher = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/wrapper/dists/gradle-8.13-bin') `
    -Filter gradle.bat -Recurse -File | Select-Object -First 1 -ExpandProperty FullName
& $gradleLauncher --version
```

설치는 이미 있는 배포본에 한한다. read-only 의존성 cache는 `modules-2`를 포함하는 부모 `caches`를 지정한다.
위 환경 변수는 같은 PowerShell 세션에만 적용된다. 저장소 설정으로 우회를 강제하지 않는다.

최종 전체 검사에서 commerce-batch와 test-pg의 Docker API 400은 사용자 PowerShell에만
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44`를 적용하고 Gradle daemon을 중지한 뒤 두 모듈 테스트를 재실행해 해결했다.
기존 값은 finally에서 복원했다. 이는 당시 테스트 환경의 API 버전 불일치 조정이며 프로덕션 설정을 변경한 것이 아니다.
UP-TO-DATE 재사용을 포함한 최종 XML 합계와 실행 범위는 [검증 근거](README.md#전체-검사-결과의-범위)에 기록한다.
