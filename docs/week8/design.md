# 쿠폰의 현재 설계와 구현 경계

C01~C05의 원본 개발 이력과 C06 작업 트리를 기준으로 한다. 고객·관리자 HTTP 및 독립 JVM 두 개의 대상 검증을 통과했고,
전체 검사에서 실패한 두 모듈도 Docker API 환경을 맞춘 재실행으로 해결했다. 최신 XML 635건은 실패·오류·skip 0이며
UP-TO-DATE 재사용을 포함한다. C06 기능 커밋을 준비한 상태다. 결과와 미확인 항목은
[구현 현황](completed/README.md), [검증 매핑](verification-plan.md)을 따른다.
선택·기각 과정은 [설계 결정 기록](trade-offs.md), 최초 과제와 변경 사항은 [요구사항](total_requirement.md)에 보존한다.

## 도메인 경계

| 모델 | 책임 |
|---|---|
| `CouponCampaign` | 이름·총량·기간·혜택, 활성 여부, 누적 발급 수와 최종 발급 검증 |
| `CouponSlot` | 사전 준비한 DB 발급 자리, AVAILABLE → ISSUED 단방향 전환 |
| `Coupon` | 사용자·캠페인·슬롯, 발급 당시 시각과 혜택을 보존한 불변 발급 결과 |
| `CouponBenefit` | 설명과 원 단위 할인 금액의 불변 값 |

생성(`create`)과 저장 상태 복원(`restore`)을 분리한다. 새 ID는 null, 저장 ID는 양수여야 하며 잘못된 저장 상태도 거절한다.
도메인은 Spring·JPA·HTTP·Redis에 의존하지 않는다. DB 시각은 application/infra가 공급한다.

- 이름은 앞뒤 공백 제거 후 Unicode code point 1~100자, 혜택 설명은 1~500자다.
- `totalQuota`와 `issuedCount`는 Java int/DB INT이며 `1 <= totalQuota`, `0 <= issuedCount <= totalQuota`다.
- 할인 금액은 양수 Java long/DB BIGINT다. 실제 할인 적용은 범위 밖이다.
- 기간은 `startsAt < endsAt`, 발급 가능 구간은 `startsAt <= dbNow < endsAt`다.
- 저장·응답 시각은 UTC이며 마이크로초 정밀도로 정규화한다.
- 생성 뒤 총량·혜택·기간을 변경하는 API는 제공하지 않는다. 활성 전환은 기존 슬롯·발급 수·쿠폰을 초기화하지 않는다.

## 캠페인과 사전 슬롯 준비

`PrepareCouponCampaignService.execute`는 공개 `REQUIRES_NEW` TX 경계다. 외부 REPEATABLE_READ snapshot에 합류하지 않는다.

| 입력 | 동작 |
|---|---|
| `Create` | 비활성 캠페인과 totalQuota개 AVAILABLE 슬롯을 한 TX에서 생성 |
| `Prepare(campaignId)` | 캠페인 현재 행을 잠그고 슬롯 수 확인. 비활성·미발급·슬롯 0개인 경우에만 전체 준비 |
| `Activation(id, true)` | 준비 슬롯 전체 수가 총량과 같을 때 활성화 |
| `Activation(id, false)` | 캠페인만 비활성화하고 재고·혜택·기간·발급 결과 유지 |

기존 managed Entity의 오래된 값을 쓰지 않도록 잠금 뒤 refresh한다. 부분 준비는 자동 보충하지 않고
`COUPON_SLOTS_NOT_READY`로 거절한다. 생성 도중 실패하면 캠페인과 이미 저장한 슬롯을 모두 rollback한다.
생성·준비와 선택적 활성화는 별도 TX이므로 활성화 실패 이전에 준비 ID를 로그에 남긴다.
[로컬 fixture와 실패 후 ID 확인 절차](completed/operations.md#로컬-데이터-준비)를 사용한다.

## DB 발급과 관계 정합성

| 테이블 | 보호 조건 |
|---|---|
| `coupon_campaigns` | 기간·총량·혜택·active·issuedCount, 발급 수 상한 CHECK |
| `coupon_slots` | `(campaign_id, state, id)` 조회 인덱스, `(id, campaign_id)` 복합 참조 키 |
| `coupons` | `UNIQUE(campaign_id,user_id)`, `UNIQUE(slot_id)`, 사용자·발급 시각·ID 목록 인덱스 |

관계는 `coupons(slot_id,campaign_id) → coupon_slots(id,campaign_id) → coupon_campaigns(id)`의 FK로 보호한다.
쿠폰에서 캠페인으로 직접 FK를 중복 추가하지 않는다. 없는 슬롯·캠페인 불일치·슬롯 중복은 DB에서도 거절한다.
명시 DDL은 `docker/payment-lab/012-coupon-campaigns-slots.sql`과 `013-coupons.sql`이다.

`IssueCouponTxService`는 독립 READ_COMMITTED TX에서 다음 순서를 따른다.

1. 사용자·캠페인 기존 쿠폰을 먼저 확인한다.
2. 현재 발급 가능 상태와 DB 시각을 검사하고 AVAILABLE 슬롯 하나를 `FOR UPDATE SKIP LOCKED`로 선택한다.
3. 쿠폰을 저장하고 슬롯의 도메인 상태를 변경한다.
4. 마지막 캠페인 행을 잠금/refresh하고, 잠금 획득 뒤 별도 SQL로 같은 쓰기 DB의 시각을 다시 읽는다.
5. 시작 전 → 종료 후 → 비활성 → 품절 순서로 최종 검증하고 발급 수를 증가시킨다.
6. 슬롯의 실제 UPDATE와 캠페인 저장을 반영하고 commit한다. 어느 단계든 실패하면 전체 rollback한다.

슬롯 UPDATE를 캠페인 잠금 뒤에 실행해 FK 검사로 공통 부모 잠금이 먼저 잡히는 문제를 피한다.
마지막 캠페인 잠금은 같은 연결의 `innodb_lock_wait_timeout`을 1초로 바꾼 뒤 기존 값으로 복원한다.
이 값은 전체 HTTP deadline이나 진행 SQL 취소 보장이 아니다.

잠금 대기 SQL의 NOW/CURRENT_TIMESTAMP는 SQL 시작 시각일 수 있어 최종 검증 시각으로 재사용하지 않는다.
마지막 검증 시 마감이면 전체 rollback한다. 마감 전 검증을 통과했다면 마감 뒤 commit은 정책상 허용한다.
후자의 실제 지연 commit 재현 여부는 [미확인 항목](verification-plan.md#미확인-항목)과 구분한다.

유일 제약의 사용자 중복만 정확히 분류해 실패 TX rollback 뒤 새 TX에서 기존 쿠폰을 조회한다.
FK·슬롯 중복·다른 DB 오류를 사용자 중복으로 숨기지 않는다. 품절·비활성·기간 거절 경합 뒤에도 기존 발급 결과를 재확인한다.

## 인스턴스별 수용과 제한 재확인

한 인스턴스의 모든 캠페인 신규 발급이 singleton Java Semaphore 하나를 공유한다.
YAML `coupon.admission.max-concurrency: 3`을 시작 시 한 번 읽으며 양수가 아니거나 형식이 잘못되면 시작을 거절한다.
변경은 재시작 시 적용한다. 요청 입력이나 런타임 한도 변경 API는 없다.

현재 사용자의 기존 완료 결과는 새 permit 없이 반환한다. 신규 수용 상태 확인·tryAcquire·실행 등록과 close는 같은 모니터에서 조율한다.
닫힘은 503, 열려 있으나 자리가 없으면 즉시 429다. 대기열·예약·전역 공유 C는 없다.
A와 B가 각 3이면 합계 6개 진행이 가능하다. 한 인스턴스의 close·release가 다른 인스턴스 상태를 바꾸지 않는다.

SKIP LOCKED 빈 결과를 즉시 품절로 보지 않는다. 같은 permit 안에서 기존 쿠폰·현재 상태·총량을 확인한다.
쓰기 전 슬롯 경합만 최초 포함 최대 4회, 50ms 간격, 최초 시도부터 200ms 예산으로 다시 시도한다.
대기는 TX 밖에서 수행하고 각 재진입 전에 이전 TX의 확정 종료와 현재 열림을 확인한다. 예산 이후 새 TX를 시작하지 않는다.
이미 INSERT한 TX나 종료 불명 TX는 재실행하지 않는다. 확인된 품절은 업무 거절, 일시 경합은 `COUPON_SLOT_BUSY`다.

## TX 관찰과 permit 반환

실행 ID는 boot UUID와 실행 UUID로 구분하고 원래 Semaphore의 handle을 보관한다.
각 발급·필요한 후속 조회 TX의 afterCompletion 신호(COMMITTED/ROLLED_BACK/UNKNOWN), DB server UUID/connection ID,
TX proxy 반환과 application 실행 종료를 함께 관찰한다. callback 안에서 Redis나 추가 DB 작업을 하지 않는다.

| 증거 | 처리 |
|---|---|
| 모든 TX 확정 종료와 application 종료 | 원 handle을 한 번 반환 |
| HTTP timeout·연결 종료만 확인 | 진행 중 permit을 반환하지 않음 |
| UNKNOWN·완료 신호 부재·TX 생성/통신 오류 | permit 보류, 인스턴스 신규 수용 닫힘 |
| 같은 handle 재반환·이전 boot ID | permit 수를 늘리지 않음 |

일반 업무 rollback과 종료 불명을 구분한다. 종료가 불명확한 TX 뒤 후속 조회나 슬롯 재시도도 시작하지 않는다.
관리자 상태는 실제 running과 종료 뒤 held를 구분하며, 반환 이력은 제한하고 미반환 실행은 유지한다.

## 수동 복구와 재시작

기본 상태는 CLOSED다. close는 새 진입만 막고 기존 DB 작업을 강제 취소하지 않는다.
recover는 CLOSED에서 요청한 현재 boot 실행 ID만 확인하고 ID별 결과를 반환한다. 자동 open하지 않는다.

복구에는 application 종료와 같은 DB의 원 세션 소멸·INNODB_TRX 부재·쿠폰↔ISSUED 슬롯 대응·DB 발급 수 정합성이 필요하다.
원 세션이 살아 있으면 Sleep이어도 거절한다. 풀 반환·쿠폰 존재·프로세스 종료 중 하나만으로 완료를 추정하지 않는다.
증거 조회의 TX proxy 예외도 해당 ID의 `NOT_ENDED`로 처리하고 나머지 ID는 계속 확인한다.
직접 전역 PROCESS 권한을 확인하며 부분 가시성이나 접근 실패는 종료 증거가 아니다.

처음 배포임을 운영자가 명시한 경로와 일반 재시작을 구분한다. 일반 재시작은 이전 DB 세션 목록이 필수이며,
운영자의 이전 process 종료·로그 목록 완전성 확인과 서버의 각 이전 세션 소멸·TX 부재·현재 DB 정지/정합성 검사를 결합한다.
빈 registry에서 최초 배포를 추정하지 않는다. 재검증 실패는 과거 성공을 무효화한다.
이전 boot의 handle을 현재 Semaphore에 반환하지 않으며, 종료/복구 조건이 충족된 뒤 별도 open 요청으로 연다.
[운영 절차](completed/operations.md#상태-확인과-개방)의 한계를 따른다.

## Redis 누적 집계

`CountingIssueCouponUseCase`는 TX 없는 Primary 진입점이다. 실제 발급 facade가 정상 반환하고 permit을 확정 반환한 뒤,
저장된 campaignId/couponId를 집계한다. 기존 결과 재요청도 같은 ID를 반영하므로 추가 증가는 없다.
Redis 실패는 복구 대상으로 기록하고 이미 확정된 쿠폰·DB 수량·성공 응답을 바꾸지 않는다.

쿠폰 전용 master 연결은 기존 `RedisProperties.master`와 database를 사용하고 command/connect timeout을 1초로 제한한다.
다른 기능의 Redis 설정과 replica 우선 읽기는 변경하지 않는다.

- 활성 집계는 ready sentinel과 coupon ID를 가진 단일 SET이다. Lua SADD로 중복을 원자적으로 방지한다.
- SCARD에서 sentinel 하나를 뺀 수가 누적 수다. 미준비·키 유실(empty)과 준비된 0건을 구분하며 접근 오류는 예외다.
- DB 기준 재집계는 캠페인 행 잠금을 유지한 READ_COMMITTED TX에서 확정 coupon ID와 issuedCount를 비교한다.
- 고유 임시 SET을 완성한 뒤 Lua로 활성 집계를 원자 교체한다. 임시 build만 60초 TTL, 활성 집계와 fence에는 TTL이 없다.
- 모든 record와 새 build가 UUID fence를 갱신한다. publish는 자기 token·준비 상태·개수를 확인한 뒤 RENAME하므로 지연된 옛 교체가 최신 값을 덮지 못한다.
- 실패 전 기존 활성 집계를 보존하고 임시 키만 정리한다. publish 결과 불명은 성공으로 단정하지 않는다.

재집계의 DB 잠금 중 Redis 대기가 발급의 1초 잠금 제한과 경합할 수 있다.
commit 뒤 process 종료로 집계 호출이 빠질 수 있으며 영속 전달/자동 재시도 저장소는 없다. DB 대조·통제된 재집계가 복구 수단이다.

## HTTP와 공개 범위

고객은 기존 `X-USER-ID` resolver가 존재를 확인한 사용자만 사용한다.
최초 발급은 201, 기존 결과는 200이며 `couponId`, `campaignId`, `issuedAt`, `benefitDescription`, `discountAmount`만 반환한다.
목록은 본인 전체 배열을 `issuedAt DESC, couponId DESC`로 정렬한다. 캠페인 마감·품절·닫힘 뒤에도 기존 결과를 유지한다.

고객 경로는 `POST /api/v1/coupon-campaigns/{campaignId}/issues`, `GET /api/v1/coupons`다.
관리자는 존재하는 사용자 ID 1의 학습 fixture이며 `/api/v1/admin/coupon-admission`의
상태·close·verify-restart·recover·open과 캠페인 counter 대조·재집계만 제공한다.
실제 인증·공개 배포·전체 캠페인 CRUD·쿠폰 사용·할인·알림은 범위 밖이다.

쿠폰 범위에서 업무 거절 409, 없는 캠페인 404, 포화 429, 닫힘·종료 불명·일시 슬롯/잠금 경합·집계 오류 503을 구분한다.
기존 다른 API 오류 envelope는 유지한다. 입력 400·관리자 403과 정확한 경로·body는 [API·운영 안내](completed/operations.md)에 있다.
