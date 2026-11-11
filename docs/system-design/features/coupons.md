# 쿠폰 발급·수용량 제어 상세설계

## 1. 상태와 읽는 기준

이 문서는 쿠폰 발급·보유 목록·동시 수용 제한·수동 장애 재개의 제품 계약과 상세 구조를 정의한다. 일곱 차례 정적 검토와 합의한 보완을 마쳤다. Q9는 실행 중인 Redis를 재사용하고 같은 master 연결의 후속 응답을 확인한 뒤 자기 token을 정리하는 절차다. 2026-11-11 사용자가 전체 설계를 최종 승인했다. 구현과 실행 검증은 미완료이며 구현 단위별 착수 승인은 별도다.

- **합의:** 사용자가 선택한 동작·범위. 다시 임의로 바꾸지 않는다.
- **구체화안:** 합의에 맞춰 작성한 클래스 배치·메서드·열/오류 코드 이름 등의 상세 계약으로, 이번 전체 설계 최종 승인에 포함한다. 계약·설계 판단 변경은 이유와 영향을 설명하고 별도 승인받는다.
- **미정:** 추가 판단이 필요한 계약. `[잠정]` 정책과 다르며 구현 기준으로 채택하지 않는다.
- **코드 확인:** 읽기만으로 확인한 현재 코드·설정. 실행 결과가 아니다.

공통 식별·응답·계층 규칙은 [API 공통 계약](../common/api-contract.md), [계층·의존](../common/architecture.md)을 유지한다. 대안·선택 이유·비용은 [쿠폰 설계 판단](../decisions/coupons.md)이 원본이다.

## 2. 범위·세 가지 개수

**합의:** 캠페인은 같은 혜택·발급 조건을 공유하는 발급 행사다. 보유 쿠폰은 사용자에게 실제 지급된 DB 행이다. 발급 실행은 신규 발급을 처리하려는 서버 실행이며 쿠폰 행과 같은 개수가 아니다.

| 경계 | 책임 | 실행 종료 뒤 |
|---|---|---|
| DB 캠페인 총량 | `issuedCount <= totalQuota` | 성공 발급 수는 감소시키지 않음 |
| DB 사용자 중복 | 같은 캠페인·사용자의 쿠폰 한 행 | 기존 결과 보존 |
| Redis 공통 수용량 C | 쿠폰 신규 발급 경로 전체의 현재 점유 | 실행·DB 종료 확인 뒤 자기 자리만 반환 |

- 필수 제품 기능: `POST /api/v1/coupon-campaigns/{campaignId}/issues`, `GET /api/v1/coupons`.
- 캠페인은 유한 발급만 지원한다. 무제한을 0·null·최댓값으로 표현하지 않는다.
- 테스트 fixture와 명시적으로 실행하는 로컬 준비 코드를 모두 둔다. 관리 CRUD API는 추가하지 않는다.
- 사용자 대기실·순번·정확한 전역 도착 순서 보장·비동기 worker·쿠폰 할인 적용·사용·만료·알림은 추가하지 않는다.
- 캠페인 시간 일정, 별도 공개 UUID, 쿠폰 Kafka 이벤트, 별도 bulkhead·회로·결과 캐시는 현재 채택 범위가 아니다.
- 기존 상품·주문·PG·이벤트·구매 활동은 보존하며 불필요하게 쿠폰과 연결하지 않는다.

사전 조회와 요청자 식별은 Redis C 제한 밖이다. C는 요청 전체·모든 DB 읽기·DB 연결 풀을 격리하는 장치가 아니다. 같은 사용자의 진행 중 두 실행도 서로 다른 token을 사용한다. 이미 완료된 결과 조회에는 새 자리가 필요 없다.

## 3. 저장 모델

### 3.1 모델·열 구체화안

기존처럼 domain의 모델이 JPA Entity를 겸한다. 모델 이름은 `CouponCampaignModel`, `CouponModel`로 한다. 아래 업무 필드는 모두 NOT NULL이며, 기존 `BaseEntity`의 PK·생성/수정 시각을 사용한다. BaseEntity 상속이 삭제·복원 기능 추가를 뜻하지 않는다. 캠페인·쿠폰 삭제 유스케이스는 제공하지 않는다.

| 테이블·열 | 타입 구체화안 | 계약 |
|---|---|---|
| `coupon_campaign.id` | BIGINT PK, 기존 IDENTITY | 숫자 ID |
| `name` | VARCHAR(100) | trim 후 1~100자 |
| `status` | VARCHAR(16) | ACTIVE / INACTIVE |
| `total_quota` | BIGINT | 1~Long.MAX_VALUE |
| `issued_count` | BIGINT | 생성 시 0, 총량 이하 |
| `benefit_type` | VARCHAR(16) | FIXED_AMOUNT / PERCENTAGE |
| `benefit_value` | BIGINT | 유형별 아래 범위 |
| `coupon.id` | BIGINT PK, 기존 IDENTITY | 보유 쿠폰 ID |
| `campaign_id`, `user_id` | BIGINT | scalar 논리 참조. 물리 FK 없음 |
| `benefit_type`, `benefit_value` | 캠페인과 같은 표현 | 발급 당시 유형·값을 복사, 이후 변경하지 않음 |
| `created_at` | 기존 BaseEntity 시각 매핑 | 발급 시각으로 사용. 별도 issued_at 열 없음 |

**합의된 혜택 범위:** 정액은 원 단위 양의 정수로 long 범위 안에서 표현하며 별도 업무상 상한은 두지 않는다. 정률은 소수 없이 1~100% 정수다. 실제 할인 계산·최소 주문액·최대 할인액·반올림 정책을 정한 것이 아니다. 공유 Money의 0 허용이나 상품 가격의 1억 원 상한을 그대로 쿠폰에 적용하지 않는다.

**합의:** 쿠폰에 캠페인 이름은 복사하지 않는다. 응답 시 연결 캠페인의 이름을 읽으므로, 이름이 바뀌면 기존 쿠폰의 표시 이름도 달라진다. 혜택은 쿠폰의 과거 값이 유지된다.

### 3.2 제약·인덱스와 모델 행동

- **합의:** `(campaign_id, user_id)` 유일 제약. 식별 이름 구체화안은 `uk_coupon_campaign_user`다. NOT NULL을 함께 적용한다.
- **합의:** 캠페인 CHECK는 `total_quota >= 1 AND issued_count >= 0 AND issued_count <= total_quota`다. 식별 이름 구체화안은 `ck_coupon_campaign_quota`다.
- 쿠폰 혜택에 추가 DB CHECK를 두는 것은 이번에 선택하지 않았다. 유형·값은 모델 생성·영속화 검증에서 보호한다. enum 문자열 매핑은 기존 `EnumNameConverter` 방식을 우선한다.
- 목록 인덱스 구체화안: `coupon(user_id, created_at, id)`. 유일 인덱스는 캠페인·사용자 정확 조회와 캠페인별 범위 조회에 사용한다. 실제 DDL·실행계획·효과는 아직 검증하지 않았다.
- FK를 두지 않아도 요청자의 존재·캠페인의 존재 검증은 제거하지 않는다. 유일 제약과 FK는 서로 대체하지 않는다.

| 모델 메서드 구체화안 | 입력·출력·실패 계약 |
|---|---|
| `CouponCampaignModel.create(name, totalQuota, benefitType, benefitValue)` | 이름·양수 총량·혜택 검증, ACTIVE·issuedCount 0으로 생성하는 안. 비활성 fixture 준비는 별도 준비 행동 사용 |
| `CouponModel.issue(userId, campaignId, benefitType, benefitValue)` | 참조 ID·혜택을 검증하고 새 행 생성. 이름 복사·쿠폰 상태 변경·할인 계산 없음 |
| 조회 결과 변환 | TX 안에서 scalar/불변 결과로 복사. Entity·LAZY 참조를 TX 밖으로 전달해 응답 조립하지 않음 |

상태 전이 API·총량 수정 API를 추가하지 않는다. 조건부 UPDATE는 DB 수량 변경 포트의 책임이다. 모델의 영속 상태를 일반 조회한 뒤 stale 값으로 덮어쓰지 않는다.

## 4. 고객 API

### 4.1 공통·응답

**합의:** 기존 `X-USER-ID` resolver와 User 존재 조회를 유지한다. HTTP 요청으로 별도 userId·혜택·수량·C·실행 token을 받지 않는다. `ApiResponse`와 기존 null 생략·숫자 변환·페이지 오류 계약을 따른다.

최초 발급 201과 기존 결과 200은 다음 다섯 필드를 공유한다. 보유 목록의 각 항목은 다섯 필드와 저장된 발급 시각 `createdAt`을 포함한다.

| 필드 | 값 |
|---|---|
| `couponId` | coupon.id, Long |
| `campaignId` | coupon.campaign_id, Long |
| `campaignName` | 응답 조회 시 읽은 캠페인 이름 |
| `benefitType` | 쿠폰에 저장된 FIXED_AMOUNT / PERCENTAGE |
| `benefitValue` | 쿠폰에 저장된 Long 값 |

발급 성공은 commit 완료로 판단한다. DB `created_at`은 쿠폰 생성 시각이며 목록 정렬에도 사용한다. 발급 재요청은 기존 쿠폰을 조회하므로 저장 시각도 보존된다. token·현재 발급 수·내부 종료 상태는 고객 쿠폰 응답에 추가하지 않는다.

보유 목록의 `createdAt`은 DB에서 읽은 `created_at`을 UTC로 변환한 ISO-8601 문자열이다. 소수점 아래 6자리와 `Z`를 사용한다. 예시는 `2026-11-10T09:30:00.123456Z`다. 마이크로초 자리까지 표현하고 남는 자리를 0으로 채운다. 같은 쿠폰은 반복 조회에서 같은 저장 시각을 반환한다.

### 4.2 발급·목록

| 요청 | 성공 계약 |
|---|---|
| `POST /api/v1/coupon-campaigns/{campaignId}/issues`, 본문 없음 | 새 발급 commit 확인 후 201. 기존 결과는 200·같은 couponId. 두 응답 모두 §4.1의 다섯 필드를 반환 |
| `GET /api/v1/coupons` | 본인 쿠폰의 `ApiResponse<PageResponse<CouponResponse>>`. 각 항목에 §4.1의 다섯 필드와 `createdAt` 포함. 빈 목록도 200 |

목록은 기존 공통 `[잠정]` 정책을 유지해 기본 page=0·size=20·최대 size=100, latest/oldest를 사용한다. latest는 created_at 내림차순·id 내림차순, oldest는 반대다. 두 정렬 열을 함께 사용한다. 캠페인의 활성 여부나 잔여 수량으로 기존 쿠폰을 목록에서 숨기지 않는다.

연결 캠페인이 실제로 없는 손상 데이터는 기존 공통의 내부 불변식 오류 원칙에 따라 500으로 드러내는 구체화안이다. INNER JOIN으로 쿠폰이 사라지거나 사전 조회의 ‘미발급’으로 잘못 바뀌지 않도록 LEFT JOIN과 결과 검증을 사용하는 안이다. 캠페인 삭제 기능을 새로 정의하는 것은 아니다.

### 4.3 업무 결과·오류

상태·우선순위는 합의이며 코드 문자열은 구체화안이다.

| 조건 | HTTP·코드 구체화안 | DB 효과 |
|---|---|---|
| 기존 쿠폰 있음 | 200 | 추가 발급 없음 |
| 캠페인 없음 | 404 COUPON_CAMPAIGN_NOT_FOUND | 없음 |
| 비활성 | 409 COUPON_CAMPAIGN_INACTIVE | 없음 |
| 활성·품절 | 409 COUPON_CAMPAIGN_SOLD_OUT | 없음 |
| Redis 자리 없음 | 429 COUPON_ADMISSION_FULL | 발급 TX 미진입 |
| 수용 닫힘·Redis 실패·획득 불확실 | 503 COUPON_ADMISSION_UNAVAILABLE | 신규 DB 발급 우회 금지 |
| 그 밖의 DB 오류·CHECK 위반·설명 불가 상태 | 기존 500 INTERNAL_ERROR 매핑 | rollback 결과를 확인하되 불명일 수 있음 |

기존 `ErrorType.INTERNAL_ERROR`의 실제 meta.errorCode는 `Internal Server Error`다. 새 문자열 `INTERNAL_ERROR`로 응답을 바꾸지 않는다.

우선순위는 사용자 식별 → 기존 쿠폰 사전 조회 → 신규 수용 → 발급 TX의 캠페인 판단이다. 없는/비활성 캠페인도 수용 거절이면 429·503이 먼저일 수 있다. 기존 쿠폰은 비활성·품절보다 우선하고, 쿠폰이 없을 때 비활성이 품절보다 우선한다.

**합의:** Redis 자리 없음 429 뒤에는 ‘그사이 발급됐는지’ 추가 조회하지 않는다. 아래 DB 실패 후 조회와 구분한다.

## 5. 계층·클래스·메서드 구체화

### 5.1 호출 구조

```text
interfaces/api/coupon/CouponV1Controller
interfaces/api/coupon/CouponAdmissionController
  → application/coupon/CouponFacade              // 상위 @Transactional 없음
      ├→ 읽기용 / 쓰기용 TransactionTemplate
      ├→ domain/coupon의 쿠폰·캠페인 저장/조회 포트
      └→ application/coupon/CouponAdmissionService
           → domain/coupon/CouponAdmissionRepository
               ← infrastructure/coupon/CouponAdmissionRedisRepository
```

**합의:** `CouponTransactionService`를 별도 추가하지 않고 TransactionTemplate을 사용한다. 같은 요청 스레드에서 순차 실행하며 새 worker나 REQUIRES_NEW를 도입하지 않는다. 별도 Control·단순 전달용 운영 Facade는 추가하지 않는 구체화안이다. 고객·제어 Controller는 CouponFacade만 호출하고 Facade 간 호출은 하지 않는다.

읽기용·쓰기용 template을 각각 초기화한 뒤 요청별로 공용 설정을 변경하지 않는다. 기본 REQUIRED이며 Facade 호출에 상위 TX를 만들지 않는다. Redis 대기 중 TX·DB 연결을 유지하지 않도록 사전 조회 template이 끝난 뒤 Redis를 호출한다. 단일 TX와 달리 시작·종료·연결 재획득 비용이 생긴다.

### 5.2 메서드 경계

| 타입·메서드 구체화안 | 책임·계약 |
|---|---|
| `CouponFacade.issue(userId, campaignId)` | 사전 조회·수용·쓰기 TX·실패 결과 확인·종료 후 반환 조율. 결과는 발급 응답의 다섯 필드와 CREATED/STORED 구분 |
| `CouponFacade.getMyCoupons(userId, page, sort)` | 읽기 template 안에서 본인 목록·총건수 조회 및 값 복사. 목록 항목에 DB에서 읽은 발급 시각 포함. Redis 무관 |
| `CouponFacade.status/close/cleanup/open` | 제어 요청 진입. §8의 수동 확인을 전제로 상태 조회·닫기·지정 token 정리·명시적 열기 조율 |
| `CouponCampaignRepository.reserveOne(campaignId, now)` | 조건부 UPDATE. 영향 행 1/0, DB 예외는 숨기지 않음 |
| `CouponCampaignRepository.findIssueData(campaignId)` | UPDATE 뒤 이름·혜택 등 필요한 scalar 값 조회 |
| `CouponRepository.save(coupon)` | 저장 후 영속 객체 반환. 호출자는 반환 객체에서 발급 응답에 필요한 ID와 혜택 값을 복사. SQL 실행 시점뿐 아니라 commit 시 발생한 예외도 바깥 template 호출에서 처리 |
| `CouponQueryRepository.findOwned(userId, campaignId)` | 본인 쿠폰의 발급 응답 필드와 연결 캠페인 이름을 조회. 없으면 Optional.empty. 캠페인 연결 손상은 ‘없음’으로 변환하지 않음 |
| `CouponQueryRepository.findOwnedPage(userId, page, sort)` | 본인 필터·안정 정렬·총건수. 응답 필드와 저장된 발급 시각을 조회. 저장 포트와 분리하는 기존 QueryRepository 패턴 |
| `CouponAdmissionService.acquire/release/close` | 공유 열림/닫힘 값과 Redis 결과 처리. 관찰한 Redis 오류·불확실 획득·정상 실행 첫 반환 0에서 닫힘. DB 종료 불명도 Facade가 이 공통 닫기 동작으로 전달. 전역 실행 목록·진행 수·DB 불명 표시를 추가하지 않음 |
| `CouponAdmissionRepository.tryAcquire(token, capacity)` | 전용 Set의 원자 획득. ACQUIRED/FULL, 예외·불명은 별개. C는 신뢰한 서버 설정에서 전달 |
| `CouponAdmissionRepository.release(token)` | 자기 token만 SREM하고 삭제 수 반환. 정상 첫 반환 0의 이상 판단은 Service 책임. 반복 반환·수동 정리의 0은 정상이며 다른 점유를 변경하지 않음 |

구체적인 RepositoryImpl·JpaRepository 위임은 기존 계층 패턴을 사용한다. CouponAdmissionService는 application의 조율 객체이며, 저장·I/O 없는 순수 Domain Service와 혼동하지 않는다. 인터페이스 이름 없는 `CouponAdmission`, PermitStore, 별도 CouponAdmissionControl은 채택 이름이 아니다.

Set 조회를 위한 제품 포트·추가 API는 두지 않는다. 운영자는 §8.3의 master 직접 조회 절차를 사용한다. 정상 반환은 해당 요청에서 한 번 수행하고, Repository 명령 자체의 반복 안전성과 Service의 첫 반환 이상 감지를 구분한다.

## 6. DB·TX 처리 의사코드

### 6.1 전체 흐름

**본인 요청의 TX 상태 확인**과 **운영자의 전체 관련 요청 수동 종료 확인**은 다른 책임이다. 각 template 호출에 대해 요청 내부에서 콜백 진입 여부와 afterCompletion 결과를 관찰한다. 콜백 첫 부분에서 진입을 표시하고 업무 SQL 전에 afterCompletion을 등록한다. 별도 실행 ID·공유 Map·전역 진행 수·전역 불명 플래그는 두지 않는다. 아래 상태 묶음은 의사코드 표현이며 별도 전달용 서비스·공개 클래스를 추가하라는 뜻이 아니다.

| 해당 template 호출의 관찰 | 분류·처리 |
|---|---|
| 콜백 진입 전 연결 풀 획득 실패 등으로 업무 SQL 미시작이 확인됨 | NOT_STARTED. 그 호출을 종료 불명으로 취급하지 않음. 요청은 500, 다른 관련 TX도 종료 확인됐다면 자기 token 반환. 반환 정상이면 수용을 닫지 않음 |
| 정상 execute 반환 | COMMITTED 경계. 값 복사까지 완료한 결과 사용 |
| 콜백 진입 후 COMMITTED 통지, execute는 예외 | commit과 응답 결과 확보 여부로 판별. 결과가 있으면 확정 성공 유지·예외 로그, 결과가 없으면 500·저장 효과 보존 |
| ROLLED_BACK 통지 | 종료 확인. 원래 업무 신호·정확한 제약 이름으로 후속 처리 |
| 콜백 진입 후 UNKNOWN 또는 완료 통지 없음 | 종료 불명. 자기 token을 유지하고 신규 수용을 닫으며 로그·500 |

**예외 타입보다 종료 판정을 먼저 적용한다.** 예를 들어 원래 중복/수량 미확보 신호가 rollback 실패로 교체됐거나 동반됐으면, 예외 원인에 그 신호가 있더라도 종료 불명에서 기존 결과 성공·품절로 바꾸지 않는다. 콜백 미진입만으로 이전 TX의 불명을 해소하지 않는다. 연결 실패 예외 이름만으로 미시작/종료를 추측하지 않고 해당 호출의 진입·업무 미시작 근거와 앞선 TX의 확인 결과를 함께 본다.

```text
issue(userId, campaignId):                         // 상위 TX 없음
  stored = readTemplate.execute:
    findOwned(userId, campaignId), 값을 복사
  if stored 있음: return STORED(stored)             // HTTP 200
  // 사전 읽기 실패는 예외 전파. Redis 자리 획득을 시작하지 않음

  token = 서버가 이 실행만을 위해 새로 생성
  if admission의 accepting == false: throw 503
  acquire = admission.acquire(token)
  if FULL: throw 429                               // 추가 DB 조회 없음
  if CLOSED / ERROR / 불명:
    신규 수용 닫기; 불명 획득은 token·실패 단계 로그; throw 503

  업무 결과 또는 오류, 후속 읽기 종류를 요청 내부에 보관
  try:
    try:
      writeTemplate.execute:
        콜백 진입 표시, afterCompletion 등록
        candidate = issueInTransaction(userId, campaignId)
        candidate를 요청 내부에도 보관              // commit 전에는 아직 성공 응답 아님
        return candidate
      commit 완료 확인 후 candidate를 CREATED로 확정
    catch 쓰기 실패:
      if 콜백 진입 후 UNKNOWN / 완료 통지 없음:
        신규 수용 닫기; token·DB 종료 불명 로그; 500 보관
      else if COMMITTED 확인:
        if candidate 확보: CREATED 확정; 사후 예외 로그
        else: 500 보관                             // commit된 쿠폰을 보상 취소하지 않음
      else if 업무 미시작 확인:
        500 보관                                   // 자동 재시도하지 않음
      else if ROLLED_BACK 확인 및 내부 수량 미확보 신호:
        후속 읽기 = resolveNotReserved
      else if ROLLED_BACK 확인 및 uk_coupon_campaign_user 위반 정확히 식별:
        후속 읽기 = findOwned                      // 있으면 STORED, 없으면 500
      else:
        기존 기술 오류 보관
    if 후속 읽기가 필요:
      try:
        readTemplate.execute:
          이 호출의 콜백 진입 표시, afterCompletion 등록
          지정한 후속 읽기와 값 복사, 결과 보관
        읽기 정상 종료 후 결과 확정
      catch 읽기 실패:
        if 콜백 진입 후 UNKNOWN / 완료 통지 없음:
          닫기·token 로그; 500 보관
        else if COMMITTED 확인 및 읽기 응답 값 확보:
          확보한 STORED 결과 유지; 사후 예외 로그
        else if ROLLED_BACK 확인 및 정의한 업무 거절:
          그 업무 거절 보관                        // 404/409 등
        else:
          500 보관                                 // 미시작 실패는 앞선 rollback 확인 유지
  finally:
    자리 획득 후 필요한 모든 DB 호출이 NOT_STARTED 또는 종료 확인이고
    해당 요청의 보호 대상 처리가 끝난 경우만:
      자기 token을 한 번 반환 시도
      명령 실패 또는 정상 첫 반환 0이면:
        닫기·로그; 확정된 업무 결과/오류 유지
    종료 불명이 남았으면:
      반환하지 않고 닫힘 유지
  보관한 업무 결과 반환 또는 오류 전파
```

후속 읽기도 바깥 try/finally 보호 범위 안에서 수행한다. 읽기 오류가 finally 실행을 건너뛰거나 앞선 불명을 삭제하지 않아야 한다. 읽기 콜백의 업무 거절 예외도 완료 통지로 해당 읽기 TX 종료를 확인한다.

`finally` 진입·스레드 예외·HTTP 연결 종료가 종료 확인 자체는 아니다. 반환 오류가 본래 업무 결과/오류를 대체하지 않게 한다. 성공 commit 뒤 반환 실패는 201을 유지하며 쿠폰 취소·issuedCount 감소를 하지 않는다. 기존 결과는 200이다. 일반 DB 오류를 중복 성공으로 바꾸지 않는다.

commit 이후 별도 쿠폰 업무나 afterCommit 콜백을 새로 추가하지 않는다. COMMITTED와 응답 값이 확인된 뒤의 부가 예외는 로그를 남기고 성공 결과를 유지한다. 응답 값을 확보하지 못하면 500이며 재요청에서 DB 결과를 확인한다. 이 규칙은 Facade가 반환할 결과의 선택이며 HTTP 직렬화·연결·응답 전송까지 성공을 보장하지 않는다.

### 6.2 쓰기 TX

```text
issueInTransaction(userId, campaignId):
  affected = reserveOne(campaignId, now)            // 첫 업무 SQL
  if affected == 0:
    내부 RuntimeException 신호                     // 쓰기 TX 종료 후 결과 판별
  if affected != 1:
    기술 오류                                     // 예상 밖 변경 건수

  campaign = findIssueData(campaignId)              // UPDATE 뒤 첫 일반 업무 조회
  없거나 값이 손상됐으면 기술 오류
  coupon = CouponModel.issue(userId, campaignId,
                             campaign.benefitType, campaign.benefitValue)
  saved = save(coupon)                             // 반환된 영속 객체 사용
  saved의 쿠폰 ID·캠페인 ID·혜택과 campaignName을 응답 값으로 복사
  return CREATED                                  // 실제 commit은 template이 수행
```

수량 UPDATE 구체화안:

```sql
UPDATE coupon_campaign
   SET issued_count = issued_count + 1,
       updated_at = :now
 WHERE id = :campaignId
   AND status = 'ACTIVE'
   AND issued_count < total_quota
```

조건부 UPDATE도 행 잠금을 사용한다. 명시적 SELECT FOR UPDATE를 하지 않는다는 뜻이지 무잠금·무대기는 아니다. **합의:** 문제가 발견되면 명시적 잠금 조회 대안을 설계에서 재검토하며, 운영 요청에서 전략을 자동 전환하지 않는다.

사전 쿠폰 조회를 쓰기 TX 안으로 끌고 오지 않는다. 쓰기 TX에서 INSERT 전 `(campaign_id, user_id)` 중복 재조회는 추가하지 않는다. 캠페인의 일반 Entity 선행 로드도 하지 않고 수량 UPDATE 뒤 scalar 혜택을 읽는다. 조건부 변경 뒤 stale 영속 상태를 다시 flush하지 않도록 한다. 기존 설계의 REPEATABLE READ·OSIV=false·별도 영속성 컨텍스트를 전제로 한 구체화이며, 실제 격리 수준·SQL 순서와 경쟁 결과는 실행 검증 대상이다.

저장 포트는 기존처럼 Spring Data save의 반환 객체를 전달한다. BaseEntity의 초기 ID와 영속화 판정 때문에 merge·PK 조회가 발생할 가능성은 실제 SQL로 확인한다. 이 PK 조회와 제외한 `(campaign_id, user_id)` 중복 재조회는 다르며, 모든 INSERT 전 SELECT를 일괄 금지하는 계약이 아니다.

INSERT 실패·유일 제약 충돌·CHECK 위반은 증가분까지 전체 rollback한다. 별도의 -1 보상 UPDATE를 하지 않는다. CHECK는 캠페인 행 내부 범위를 보호할 뿐 실제 쿠폰 COUNT와 issuedCount 일치를 증명하지 않는다.

### 6.3 UPDATE 0건·중복 충돌

UPDATE의 0은 수량 값 0이 아니라 **조건에 맞아 변경한 행이 없음**이다. 그 자체로 품절·미발급·기발급 여부를 구분할 수 없다.

```text
resolveNotReserved(userId, campaignId):             // 이전 쓰기 TX 종료 후 새 읽기 TX
  stored = findOwned(userId, campaignId)
  if 있음: return STORED                           // 200
  campaign = 캠페인 조회
  if 없음: 404
  if INACTIVE: 409 비활성                          // 품절보다 우선
  if issuedCount >= totalQuota: 409 품절
  else: 500                                       // 실패 원인을 현재 상태로 설명 못함
```

실패 원인 분류는 새 읽기에서 확인한 결과이며 실패 당시 상태를 과거 시점으로 재구성하는 기능은 아니다. 기술 실패를 품절로 숨기거나 자동 TX 재시도하지 않는다.

동시 중복의 두 경우를 모두 유지한다.

1. 잔여량이 있어 두 요청 모두 UPDATE 1건이면 두 번째 INSERT의 유일 제약이 최종 방어한다. 실패 TX 종료 후 기존 결과를 읽는다.
2. 같은 사용자의 앞 요청이 마지막 수량을 발급하면 뒤 요청은 UPDATE 0건이라 INSERT·유일 제약 검사에 도달하지 않을 수 있다. 기존 결과 우선 조회로 200을 반환한다.

현재 `ConstraintViolationClassifier`는 결제 제약만 허용 목록에 두고 있다. 구현 시 정확한 쿠폰 유일 제약 이름을 식별하도록 확장해야 한다. 단순 SQL 오류 코드나 모든 DataIntegrityViolationException을 쿠폰 중복으로 취급하지 않는다. commit 시 예외까지 template 호출 바깥에서 포착하며 rollback 불명이면 후속 조회 성공으로 종료를 확정하지 않는다.

## 7. Redis 수용·반환

**코드 확인:** modules/redis는 기본 REPLICA_PREFERRED와 별도 MASTER 연결을 제공한다. `redisTemplateMaster`는 `RedisTemplate<String, String>`이고 key/value/hash serializer는 String이다. 앱의 connect/command timeout은 각각 200ms이며 실제 장애 시 대기 시간 실측값이나 HTTP 전체 deadline이 아니다. local 설정은 master localhost:6379·replica localhost:6380, 테스트 fixture는 실제 생성한 컨테이너 주소로 덮어쓴다.

기존 master template을 qualifier로 주입해 사용한다. 새 연결·template·공용 RedisConfig 안의 쿠폰 로직을 추가하지 않는다. unchecked cast를 사용하지 않는다. 전용 Set 키는 `commerce:coupon-admission:v1:permits`다. 같은 실행 환경의 신규 발급 경로는 캠페인과 무관하게 같은 키·같은 C를 사용한다. 고객·제어 API로 키를 받거나 변경하지 않는다. 공유 Redis를 사용하는 검증의 실행 격리·master 확인은 §10.1을 따른다.

**합의된 수용량 설정:** `commerce.coupon.admission.capacity`의 모든 프로필 기본값은 3이다. HTTP 입력이나 실행 중 변경 API로 받지 않는다. 1 이상의 정수만 허용하며 명시된 값이 0·음수·형식 오류 등 유효하지 않으면 앱 기동에 실패한다. 설정을 생략하면 기본값 3을 사용한다. 테스트는 목적에 따라 시작 시 값을 덮어쓸 수 있고, Q7에서는 실제 값 3을 확인한다. 기본값은 적정 운영 용량·최대 TPS의 실측 결론이 아니다.

Lua는 `apps/commerce-api/src/main/resources/redis/` 아래 수용 스크립트로 두는 안이다.

```text
입력의 capacity가 양의 정수이고 token이 비어 있지 않은지 검증
키 타입 등 오류를 쓰기 전에 드러냄
같은 token이 이미 Set에 있으면 ACQUIRED
SCARD >= capacity이면 FULL
그 외 SADD token 후 ACQUIRED
```

SISMEMBER·SCARD·SADD를 하나의 Lua 실행으로 묶는다. 결과 1/0과 예외·timeout·비정상 결과를 구분한다. 같은 token 재확인은 동일 실행에만 의미가 있다. 다른 HTTP 실행과 공유하지 않으며 고객 인증·요청 멱등 키·couponId로 사용하지 않는다. Redis 불확실 시 신규 DB 발급을 시작하지 않는다. Lua의 원자 실행을 SQL rollback과 동일시하지 않는다.

반환은 `SREM permitsKey ownToken`이다. 재고 복구·무조건 카운터 감소·Set 전체 삭제가 아니다. 같은 반환 반복이나 없는 token 반환이 다른 점유를 바꾸지 않아야 한다. 명령의 삭제 수와 실패 예외를 구분한다.

| 반환 맥락 | 결과·처리 |
|---|---|
| ACQUIRED 확인된 정상 실행의 첫 반환 | 1이면 반환 완료. 0이면 있어야 할 점유가 없다는 이상 신호이므로 로그·신규 수용 닫힘. 확정된 업무 결과는 유지 |
| 같은 명령 반복·수동 cleanup 재호출·알 수 없는 token 반환 검증 | 0도 정상. 다른 활성 token 불변. 이 반복 안전성을 정상 첫 반환의 이상 감지와 혼동하지 않음 |
| 명령 예외·timeout·비정상 결과 | 로그·신규 수용 닫힘. 업무 결과는 유지하고 남은 점유는 수동 확인 대상으로 둠 |

정상 첫 반환 0은 구체적인 유실 원인을 확정하는 증거는 아니다. Redis 상태 유실·외부 제거 등의 가능성을 무시하지 않기 위한 닫힘 조건이며, 새로운 전역 추적 장치는 추가하지 않는다.

TTL·주기적 강제 회수·Redis 실패 시 DB 신규 발급 우회·로컬 count 대체 수용은 넣지 않는다. Redis PONG·빈 Set은 기존 실행·DB 종료 증거가 아니다. 획득 timeout으로 token이 늦게 추가될 가능성이 남으면 신규 DB 발급을 시작하지 않고 수용을 닫아 둔다. 해당 token과 요청·업무 결과는 오류 로그로 식별한다. 늦은 명령과 자기 token 정리의 판단은 §8.3을 따른다.

## 8. 제어 API·수동 장애 재개

### 8.1 합의된 경계

- **모든 프로필에서 동일하게 제어 API를 등록**한다. local·test·dev·qa·prd별 제한이나 별도 활성화 옵션·제어 키·관리자 로그인을 추가하지 않는다. local·test 전용 등록 대안은 채택하지 않았다.
- 현재 local·test의 loopback 바인딩을 다른 프로필로 확대하지 않는다. loopback 제한이 없는 실행 환경에서는 이 인증 없는 제어 API가 외부에서 호출될 수 있다는 한계를 수용한다. 제어 API 제공이 운영 환경의 인증·다중 인스턴스 복구 안전성 보장을 뜻하지 않는다.
- 앱 시작 시 신규 수용은 모든 프로필에서 기본 닫힘이다. 직접 실행에서는 제어 API로 명시적 열기를 수행하고, 자동 테스트는 같은 공통 로직을 직접 호출한다. 제어 API HTTP 경로 자체도 별도 검증한다.
- 기존 관리자 `/api-admin/**` 접근 규칙을 완화하지 않는다. 제어 API를 그 경로에 붙이거나 고객 `X-USER-ID`를 제어 인증으로 사용하지 않는다.
- 장애 시 닫는 것은 신규 발급 수용이다. 기존 쿠폰·목록 조회는 DB가 정상일 때 유지한다. 이미 수용된 실행을 단순 닫힘·HTTP 연결 종료로 취소했다고 간주하지 않는다.
- DB 발급 commit 확인 뒤 반환 실패는 성공 응답 유지·로그·신규 수용 닫힘이다. 자동 열기·TTL 회수는 없다.
- 실제 DB 업무가 시작됐으나 종료를 확인하지 못하면 token 유지·로그·신규 수용 닫힘이다. token만 남기고 나머지 자리로 계속 수용하는 대안 대신, 불명 실행이 해결되지 않은 상태를 드러내고 확인하기 위해 전체 신규 수용을 닫는다. 업무 미시작이 확인된 실패는 §6.1처럼 구분한다.
- 종료 근거와 Redis token 목록은 다른 정보다. 종료 확인 후 자기 범위의 비활성 token만 정리하고 명시적으로 재개한다.
- **장애 재개는 통제된 로컬 환경의 수동 확인 절차로 수행한다.** API는 전체 실행 종료·DB 상태·소유권을 독립적으로 자동 보증하지 않는다. 확인 책임과 중단 조건은 §8.3에 명시한다.
- **전체 실행 Map은 제외한다.** 다른 전체 실행 컬렉션, 전역 진행 수 카운터·DB 종료 불명 플래그, 재개용 Java read/write lock, token의 앱 실행 ID prefix, 추가 실행 DB 테이블·파일 표식을 넣지 않는다. 각 요청의 기존 token과 본인 TX 결과 처리는 유지한다.
- 요청마다 새 상태 목록을 만들지 않는다. 닫기·열기에 필요한 공유 `accepting` 값 하나만 수용 service에 둔다. 구체화안은 AtomicBoolean이며 초기 false다. 이 값은 실행별 기록이나 Redis 점유량이 아니다.

### 8.2 제어 진입점 구체화안

| 요청 | 기능·응답 구체화안 | 전제·한계 |
|---|---|---|
| `GET /api/coupon/admission` | 200, data에 `accepting` | 앱의 허용 상태이지 실행 종료·Redis 건강 증거가 아님 |
| `POST /api/coupon/admission/close` | accepting=false, 200과 현재 accepting | 반복 가능. 진행 작업 취소·완료 대기 없음 |
| `POST /api/coupon/admission/cleanup` | 닫힘에서 지정 자기 token만 SREM, 200과 removedCount | 운영자가 §8.3의 종료·범위 확인을 먼저 완료. 이 전제를 API가 자동 검증하지 않음 |
| `POST /api/coupon/admission/open` | accepting=true, 200과 현재 accepting | 운영자가 모든 선행 확인·정리를 마친 뒤 호출. 실행 종료 자동 판정 없음 |

cleanup 본문 구체화안은 `{"tokens":["실험에서 확인한 기존 token"]}`다. JSON을 읽은 뒤 확인한 null·빈 목록·빈 token 등 입력 검증 오류는 400 INVALID_REQUEST, 열린 상태에서 cleanup은 409 COUPON_ADMISSION_OPERATION_REJECTED를 사용한다. 읽을 수 없는 JSON·본문 매핑 실패는 기존 ApiControllerAdvice의 400 BAD_REQUEST 매핑을 따른다. 그 실제 errorCode는 `Bad Request`이며 공통 오류를 새 문자열로 바꾸지 않는다. Redis 명령 실패는 503 COUPON_ADMISSION_UNAVAILABLE이며 계속 닫힌 상태를 유지한다. 없는 token의 SREM 결과 0은 반복 정리에서 정상일 수 있다. 신규 UUID·소유권 boolean을 입력받아 종료 증거로 간주하지 않는다.

다른 Controller·Service가 별도 accepting 값을 갖지 않는다. Redis 획득·반환 명령의 관찰된 오류·불확실한 획득 결과, 정상 첫 반환 0, DB 종료 불명을 처리할 때 공통 닫기 동작으로 accepting=false와 로그를 남긴다. 상태 유실을 모두 자동 감지하는 별도 장치는 추가하지 않는다. 명령 예외·기대 점유 부재와 감지되지 않은 상태 유실을 구분한다.

닫힘 값을 확인한 신규 요청은 Redis 획득·신규 발급 TX를 호출하지 않고 503으로 끝낸다. 사전 쿠폰 조회는 유지한다. 닫힘 전 검사에 통과해 이미 진행 중인 요청은 취소됐다고 간주하지 않는다. **이후의 늦은 실행까지 포함해 알려진 모든 실험 요청을 종료 확인하는 책임은 수동 절차에 있다.** 재개·정리 조작은 운영자 한 명이 순서대로 수행하며 동시 운영 조작을 자동 조율하는 기능은 이번에 추가하지 않는다.

### 8.3 수동 확인·정리·재개 가이드

#### 적용 전제

1. 동일한 로컬 앱 한 개를 유지한다. 실제 사용하는 Redis master 주소·DB·전용 Set 키를 기록한다. 앱 재시작 복구·다중 인스턴스 자동 장애 전환에 적용하지 않는다.
2. 이 실험이 준비한 전용 키와 초기 상태를 확인하고 다른 실행·운영 자료와 섞지 않는다. 혼합된 기존 키를 FLUSHALL·DEL로 초기화하지 않는다.
3. 시작할 쿠폰 요청의 사용자·캠페인·건수를 미리 정하고 호출별 결과를 보관한다. 확인 중에는 추가 발급 요청·캠페인 준비·관련 데이터 변경·동시 제어 조작을 하지 않는다.
4. 알려지지 않은 발급 주체·요청이 섞이면 이 가이드를 적용하지 않는다. 자기 범위는 ‘그 Redis에 보이는 전부’가 아니라 이번 실험이 생성한 token이다.

#### 절차와 중단 조건

| 순서 | 수행·확인 | 중단 조건 |
|---|---|---|
| 1 | 오류 후 GET 상태로 accepting=false 확인. 필요하면 close 호출 | 상태 확인 실패 또는 계속 열려 있음 |
| 2 | 미리 정한 모든 요청의 실제 처리 완료와 결과 대조 | 요청 누락·미확인·client timeout·연결 끊김·일반 500을 완료로 취급하지 않음 |
| 3 | 관련 사용자 쿠폰과 캠페인 발급 수·실제 행 수를 기대 결과와 비교 | DB 조회 실패·중복·총량 초과·발급 수 불일치·설명 못하는 결과 |
| 4 | 실제 앱 Redis master endpoint를 복구하고 아래 직접 명령으로 전용 Set 조회. 오류 로그·실험 관찰로 기존 자기 token과 불명 명령의 종류 확인 | 출처 모르는 token, 끝나지 않은 실행, 늦은 획득 가능성, 종류나 영향이 불명확한 명령이 있으면 닫힘 유지 |
| 5 | 종료 확인된 자기 token을 지정해 cleanup 호출 후 Set 재조회 | Redis 실패·일부 처리·응답 유실이면 계속 닫힘. 성공분을 되돌리지 않고 재조회한 잔여 자기 token만 재검토 |
| 6 | 위 확인이 모두 끝나고 안전한 정리 결과를 확인한 뒤 open 호출 | 선행 확인이 하나라도 미완료면 open 호출 금지 |

관련 요청과 DB 작업의 종료 및 실제 발급 결과를 확인한 뒤, 남은 불확실성이 종료된 실행의 자기 token에 대한 늦은 `SREM`뿐이면 수동 정리·재개를 진행한다. 정상 반환과 cleanup이 어느 순서로 실행돼도 같은 token만 제거하며 새 실행은 별도 token을 사용한다. 자기 잔여 token만 cleanup하고 master 재조회로 정리를 확인한 뒤 명시적으로 연다. 늦은 `EVAL`이 token을 추가할 수 있거나 명령의 영향이 불명확하면 닫힘을 유지한다.

#### 요청 완료의 증거

동기 Controller는 Facade의 모든 관련 처리 뒤에 응답한다. 정상 201은 쓰기 template commit 완료 뒤, 기존 결과 200은 읽기 template 종료 뒤, 업무 거절은 관련 TX 종료 뒤 반환해야 한다. **그 응답 시점을 통합 테스트로 먼저 검증한 뒤**, 정해진 요청들의 완전한 응답 수신을 수동 완료 근거로 사용한다. 응답 이후 쿠폰 DB 작업을 계속하는 비동기 처리를 추가하지 않는다.

단일 운영자가 GET으로 닫힘을 확인하고 닫힘을 유지한 상태에서 보낸 요청의 503은 Redis 획득 미호출로 판별한다. 그 외 503은 요청과 연결된 단계 관찰로 획득 호출 여부를 확인한다. 확인 근거가 없으면 늦은 획득 가능성을 열어 두고 닫힘을 유지한다.

429·Redis 획득 실패 503은 해당 요청이 신규 DB 발급을 시작하지 않았다는 계약과 대조한다. 다른 요청들의 종료를 증명하는 응답은 아니다. client timeout·연결 종료·일반 500은 DB 종료 근거가 아니다. 이 최소 가이드는 그 불확실성을 임의 해소하지 않고 재개를 보류한다. 로그가 없다는 사실·오래 기다림·DB COUNT 일치로 대체하지 않는다.

자동 테스트는 알려진 요청만 시작하고 실제 서버 처리의 완료를 확인한다. 직접 Controller/Facade를 수행한 worker라면 유한한 종료 대기와 TX 완료 결과를 확인한다. 실제 HTTP client future의 timeout/실패 완료는 서버 종료 증거가 아니므로, 서버 측 테스트 관찰이나 완료 후 응답을 연결해 확인해야 한다. 그 증거를 얻지 못한 실행은 실패·닫힘으로 남기며 정리·재개 성공으로 기록하지 않는다. 이는 테스트가 소유한 작업의 확인이며 제품에 전체 실행 Map을 추가하는 계약이 아니다.

#### 실제 DB 결과 확인 예시

7은 예시이며 해당 실험에서 준비한 캠페인 ID로 바꾼다. 결과 확인은 실행 종료 확인과 별도다.

```sql
SELECT c.id, c.total_quota, c.issued_count,
       COUNT(q.id) AS actual_coupon_count
  FROM coupon_campaign c
  LEFT JOIN coupon q ON q.campaign_id = c.id
 WHERE c.id = 7
 GROUP BY c.id, c.total_quota, c.issued_count;

SELECT id, user_id, campaign_id, benefit_type, benefit_value, created_at
  FROM coupon
 WHERE campaign_id = 7;
```

사용자별 발급 효과·쿠폰 ID·혜택·중복 여부를 예상 요청 결과와 대조한다. issuedCount와 actualCouponCount가 같아도 미완료 요청이 없다는 증거는 아니다.

#### 자기 token·오류 로그

**의무 로그 경로:** 획득 불명·DB 종료 불명·반환 실패·정상 첫 반환 0. 해당 요청의 기존 token·campaignId·userId·실패 단계와 이미 확인한 업무 결과를 남긴다. 결과를 확인하지 못했으면 불명으로 표기하며 성공·rollback으로 꾸미지 않는다. token이 남거나 기대 점유가 사라진 경로를 수동 요청 결과와 연결할 수 있어야 한다. 새 로그 DB·쿠폰 token 열·전체 실행 이력 저장소를 추가하지 않는다. 테스트가 서버 생성 token을 관찰한 값도 생성 주체·실행 범위를 아는 근거로 사용할 수 있다.

token을 받았다는 사실, UUID 형태, 전용 키에 있다는 사실만으로 종료를 인정하지 않는다. 2번에서 종료 확인한 실험 요청과 연결된 token만 지정한다. Redis Set은 정리 대상 확인에 사용한다. 출처가 불명확한 token과 위 조건을 충족하지 못한 늦은 명령은 닫힘 유지 사유다.

Set 조회는 앱의 실제 master host·port·database를 확인한 뒤 Redis CLI로 직접 수행한다. 아래 변수는 그 확인값이며 local 기본값·replica 주소로 추측해 대체하지 않는다. 인증이 설정된 환경은 해당 접속 설정도 일치시킨다. 조회 API와 조회용 Repository 포트는 추가하지 않는다.

```sh
redis-cli -h "$COUPON_REDIS_MASTER_HOST" -p "$COUPON_REDIS_MASTER_PORT" -n "$COUPON_REDIS_DATABASE" SMEMBERS commerce:coupon-admission:v1:permits
```

정리 후에도 같은 master·database·키로 재조회한다. CLI 조회 결과 자체로 실행 종료·소유권을 판정하지 않는다. local/test에서 create로 앱을 재시작하면 DB 데이터는 재생성돼도 Redis 점유가 남을 수 있다. 이 가이드는 종료 근거를 잃은 재시작 후 token 정리를 허용하는 복구 절차가 아니며, 그런 잔여 점유를 임의 삭제해 재개하지 않는다. 테스트 실패 후 기록된 자기 점유의 사후 정리는 §10.1의 별도 조건을 따른다.

### 8.4 제어 동작 의사코드와 보장 한계

```text
status(): return accepting

close(): accepting=false; return accepting

cleanup(tokens):
  // 호출 전제: 운영자가 §8.3에 따라 관련 실행·DB 종료/결과/범위 확인 완료
  accepting이면 409
  입력 형식 검증
  전용 Set에서 지정한 자기 token만 SREM
  명령 오류·timeout이면 accepting=false, 로그, 503
  return removedCount  // 없어 이미 제거된 token은 0일 수 있음

open():
  // 호출 전제: 운영자가 §8.3의 확인과 정리를 모두 완료, 다른 제어 조작 없음
  accepting=true; return accepting
```

이 API는 수동 운영 명령이며, 호출자의 확인을 boolean 값 하나로 증명하거나 전체 실행 종료를 독립적으로 자동 보증하지 않는다. 잘못된 시점의 cleanup/open·출처가 틀린 token 지정까지 자동 차단하는 기능이 아니다. 순차 운영·알려진 요청·단일 앱 전제를 깨면 가이드의 보장 범위를 벗어난다. 전제를 검증 기록에 남기며 일반 공개/다중 인스턴스 운영 복구로 설명하지 않는다.

API 입력/오류 코드·TX 완료 관찰의 상세 구체화안까지 전체 설계 최종 승인을 받았다. 이 절차는 아직 실행하지 않았으며 실제 테스트 증거는 구현·검증 단계에서 확보한다. 구현 단위별 착수 승인은 별도다.

## 9. 준비 코드·fixture

**합의:** 자동 테스트용 조건 fixture와 직접 실행용 준비 코드를 모두 둔다. 준비 코드는 데이터 준비만 담당하며 Redis 수용을 자동으로 열거나 쿠폰을 미리 발급하지 않는다.

| 경로 | 계약 |
|---|---|
| 테스트 fixture | 필요한 사용자·캠페인을 실제 DB에 준비하고 생성 ID 반환. worker 시작 전 준비 commit. 검증 목적별 활성·비활성·총량·사용자 수를 지정하며 로컬 기본 데이터에 의존하지 않음 |
| 직접 실행용 준비 코드 | `commerce.coupon.fixture.enabled=true`를 명시한 앱 시작 때 한 번 새 데이터 추가. 기본값 false. 저장된 ID는 DB에서 직접 확인해 사용 가능하며 ID·요청 예시 출력은 편의 수단 |

### 9.1 직접 실행·재실행 계약

- 기본 데이터: 사용자 8명, 이름 `로컬 쿠폰 캠페인`인 ACTIVE 캠페인 1개, totalQuota 5·issuedCount 0, FIXED_AMOUNT·benefitValue 5000. 보유 쿠폰은 0부터 시작한다. C 기본값 3과 발급 총량 5는 별도 값이다.
- 다시 옵션을 켜서 실행하면 새 사용자·새 캠페인을 추가한다. 이름으로 기존 캠페인을 찾아 덮어쓰거나 재사용하지 않는다. 이전 캠페인·쿠폰·issuedCount를 수정·삭제·리셋하지 않는다. 새로운 기본 데이터가 필요 없으면 옵션을 끈다.
- 기존 DB의 사용자·캠페인 ID도 발급 API에 그대로 사용할 수 있다. 출력 예시를 거쳐야만 호출 가능한 구조가 아니다. 실제 보유 쿠폰은 발급 API로 만든다.
- 소규모 기본 데이터는 수동 기능 확인용이다. 대량 사용자·충분한 총량·동시성·장애 조건은 자동 테스트 fixture에서 별도로 준비한다. 자동 테스트에서 준비 옵션은 기본 false다.

### 9.2 준비 코드 구조·순서 구체화안

main 소스의 `config/CouponLocalFixtureConfig`에서 위 옵션이 명시적으로 true일 때만 `ApplicationRunner` 빈을 등록한다. 별도 준비 앱·관리 CRUD·전달용 Runner 클래스를 추가하지 않는다. 이 구성은 앱 실행물에 포함되지만 기본 실행에서는 데이터 준비가 수행되지 않는다.

준비용 구성은 TransactionTemplate과 기존 사용자·포인트 JPA 저장소, 신규 캠페인 JPA 저장소를 사용한다. config의 준비 구성에 한정된 의존이며 CouponFacade가 infrastructure에 의존하게 만들지 않는다. 기존 사용자 fixture처럼 사용자는 잔액 0 Point와 함께 생성해 사용자 초기화 계약을 지킨다. 자동 테스트의 기존 test 소스 fixture를 main에서 참조하지 않는다.

```text
준비 옵션 true인 앱 시작:
  TransactionTemplate.execute:
    8회: UserModel.create → save 반환 객체의 ID → 잔액 0 Point 저장
    CouponCampaignModel.create(기본 이름, 5, FIXED_AMOUNT, 5000) 저장
    생성 사용자 ID들·캠페인 ID를 값으로 복사
  commit 정상 반환 후 선택적으로 ID·요청 예시 출력
  앱을 계속 실행                             // 수용 상태는 여전히 기본 닫힘
```

준비 과정의 예외는 앱 기동 실패로 전달한다. 준비 TX의 rollback을 확인한 경우 이번 실행에서 추가하던 사용자·포인트·캠페인 행은 남지 않는다. commit 여부가 불명확하면 실제 DB 결과를 확인한다. 앱 시작 시 `ddl-auto=create`로 수행한 스키마 재생성은 준비 TX보다 앞선 작업이다.

실행 예시는 저장소 루트에서 수행하는 명령이다. 아래는 실행 결과가 아니라 호출 방법이다.

```sh
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=local --commerce.coupon.fixture.enabled=true --spring.jpa.hibernate.ddl-auto=update'
```

이후 같은 DB 데이터를 재사용하려면 준비 옵션 false와 update로 실행하고 DB에서 기존 ID를 확인한다. 새로 확인할 때는 create를 선택할 수 있다.

**코드 확인과 설정 경계:** 현재 local/test JPA 기본 ddl-auto는 create다. 수동 확인에서는 실행 옵션으로 create/update를 선택할 수 있으며 이 문서 작업은 공통 기본 설정 파일을 바꾸지 않는다. create에 의한 앱 시작 시 DB 재생성과 준비 코드 자체의 기존 데이터 보존을 혼동하지 않는다. 자동 테스트의 DB 초기화는 기존 fixture·테스트별 격리 조건을 유지하며 수동 설정 선택으로 일괄 변경하지 않는다. update 사용이 운영 마이그레이션·재시작 후 장애 복구 보장을 뜻하지 않는다.

## 10. 검증 기준 — 전부 실행 전

아래는 통과 기록이 아니라 구현·검증 시 만족해야 할 조건이다. 구현 단위별 실제 실행 결과와 Q별 부분 근거는 [쿠폰 실행 기록](../verification/coupons.md)에 따로 둔다. 기존 테스트 파일의 존재나 과거 실행 결과를 신규 기능의 검증 근거로 사용하지 않는다. 장애 재개 사례는 §8의 수동 절차 전제와 실제 서버·TX 완료 증거를 연결해 검증한다.

| ID | 검증 | 기대값·관찰 |
|---|---|---|
| Q1 | 사용자·정상 발급·본인 목록 | 기존 식별 오류 유지, 최초 201·쿠폰 1·issuedCount 1, 본인 목록 같은 ID, 타인 비노출 |
| Q2 | 순차 중복·품절/비활성 뒤 기존 결과 | 최초 201과 재요청 200 모두 §4.1의 다섯 필드 반환. 같은 ID·혜택, 추가 효과 0, 이름은 조회 시 캠페인 값 |
| Q3 | 동시 동일 사용자 | 충분한 수용량과 순서 통제로 둘 이상의 실행이 사전 조회를 통과해 DB 경쟁에 참여하도록 준비. 실제 효과 1. 잔여량이 있는 유일 제약 충돌과 마지막 수량 UPDATE 0건을 각각 검증해 기존 결과 반환 |
| Q4 | 마지막 총량 경쟁 | 총량 5·서로 다른 8명이 실제 DB 경쟁 시 5 발급·3 품절·쿠폰 5·issuedCount 5. Redis 거절을 품절로 세지 않음 |
| Q5 | 증가 후 INSERT 실패 | 실제 증가 SQL 뒤 실패 주입·전체 rollback·후속 새 조회·종료 뒤 자기 token 반환 |
| Q6 | commit 후 응답 유실 | 재요청에 기존 쿠폰, 추가 발급 0 |
| Q7 | 실제 Redis C=3 | 충분한 총량, 3명의 실제 요청을 획득 직후·DB TX 전에 deadline 있는 barrier로 유지. token 3에서 네 번째 429·DB 발급 경로 미진입·쿠폰 없음. 한 실행 종료·반환 뒤 재요청 수용, 최종 쿠폰·발급 수·token 0 |
| Q8 | 반환 맥락과 반복 안전성 | 정상 첫 반환 0은 결과 유지·로그·닫힘. Repository 반복/알 수 없는 token 반환과 cleanup의 0은 정상이며 다른 활성 token 불변. 하나의 token을 여러 HTTP 실행이 공유하는 실험으로 대체하지 않음 |
| Q9 | 실제 앱 Redis endpoint 일시정지 | 현재 실행 중인 Redis에 연결해 pause/unpause. 신규 503·DB 신규 효과 0·기존 POST 결과/목록 유지 확인. 서버 요청 종료 후 같은 master 연결의 후속 CLIENT ID/INFO 응답과 장애 전후 연결 ID·run_id 일치 확인, 자기 token 정리·master Set 0개 확인, 수용 닫힘 유지 |
| Q10 | commit 뒤 반환 실패 | 실제 DB commit 뒤 반환 직전에 기존 Redis 컨테이너 일시정지. 쿠폰·issuedCount·201 보존, 닫힘. 해제 후 서버 호출·TX 종료·DB 결과 확인. 종료된 실행의 자기 token에 대한 늦은 SREM만 남을 수 있는 조건에서 자기 잔여 token 정리·명시 재개·새 요청 수용 확인 |
| Q11 | HTTP timeout·연결 종료·DB 종료 불명 | 실제 MySQL에서 진행 중 HTTP 요청의 조기 token 반환 없음 확인. 별도 TX 관리자 대역으로 UNKNOWN/완료 통지 없음 시 token 유지·로그·닫힘·재개 보류 검증. 실제 DB 통신 장애 재현 결과와 구분 |
| Q12 | 시간 경과 | 활성 token에 TTL 자동 회수 없음. 시간 경과만으로 새 수용 자리가 되지 않음 |
| Q13 | TX·SQL·제약 | Redis 호출 중 TX 없음, 증가+INSERT 동일 TX, INSERT 전 사용자·캠페인 중복 재조회 없음(PK/merge 조회와 구분), CHECK 위반은 중복 성공·품절로 변환하지 않음, 실제 named unique 식별. 대역으로 미시작 실패와 COMMITTED+예외 분기도 확인 |
| Q14 | 제어·수동 재개 절차 | 닫힘 확인 후 시작한 신규 요청은 Redis/DB 발급 미진입, 이미 진행 중인 알려진 요청은 완료 확인 후 정리. 전 프로필 동일 제어 API·기본 닫힘·키 없음, 공통 test 열기 경로, 열린 상태 cleanup 거절, 반복 정리 안전성, JSON 매핑 오류/입력 검증 오류 구분. 종료 불명·Redis 실패 후 닫힘 유지 |
| Q15 | DTO·목록·모델 경계와 준비 설정 | 이름 길이·정액/정률·유한 총량 경계, 안정 정렬·페이지 오류, 현재 이름/과거 혜택 구분, 연결 손상으로 조용히 쿠폰 누락 없음. 목록의 `createdAt`이 같은 쿠폰의 DB 저장 시각에 대응하며 UTC·소수점 6자리·Z 형식과 반복 조회 시 값의 동일성을 지킴. C 생략=3·잘못된 값 기동 실패, 준비 옵션 기본 off·on 때 8명/5장 생성·보존 DB에서 재실행은 새 데이터 추가·기존 효과 불변 |
| Q16 | 회귀·실험 정리 | 관련 기존 기능·Checkstyle·ArchUnit, 시작한 worker·DB 작업 종료 뒤 자신이 만든 자원만 정리 |

Q4는 충분한 Redis 용량 또는 DB 발급 callback 직접 경쟁으로 DB 불변식을 분리 검증한다. Q7은 mock·thread-local count·네 요청 단순 동시 전송으로 대체하지 않는다. barrier 실패 시 해제·종료 확인을 수행하며 살아 있는 작업과 겹쳐 DB/Redis를 지우지 않는다. 기존 RedisCleanUp의 FLUSHALL이나 관계없는 cache·컨테이너 전체 삭제를 사용하지 않는다.

실제 주소·버전·C·총량·사용자 수·시작/종료·실행 명령·HTTP 집계·DB 상태·token·한계를 기록한다. 0건·skip·연결 실패·과거 성공은 이번 통과가 아니다. C=3은 적정 운영 용량·최대 TPS·C=3000 안전성 검증이 아니다. 성능 비교·대용량 추적 비용 실험은 승인 없이 추가하지 않는다.

### 10.1 실제 Redis 장애·동시성 검증 장치

- Q9·Q10은 현재 실행 중인 로컬 Redis를 사용하는 명시 실행 검증이다. 한 테스트 클래스 `CouponLocalRedisFaultIntegrationTest`에 두 사례를 두고 클래스 수준 `@EnabledIfEnvironmentVariable(named = "COUPON_LOCAL_REDIS_FAULT_TEST", matches = "true")`로 활성화한다. 기본 실행에서 비활성인 사례는 미실행으로 기록하며 필수 장애 검증 완료는 명시 실행 결과로 판단한다.
- 명시 실행은 같은 test 작업에서 환경변수와 클래스 필터를 지정한다. 테스트 클래스의 초기화 코드에서 Redis 기동·장애 조작을 먼저 수행하지 않는다. 활성화된 실행에서 대상 앱 master/replica host·port·database와 제어할 Docker 컨테이너를 확인한다. 로컬 기본 master는 localhost:6379이며 실제 실행 설정을 우선한다.

```sh
COUPON_LOCAL_REDIS_FAULT_TEST=true \
COUPON_LOCAL_REDIS_FAULT_LOG="$PWD/docs/week8/verification/coupon-local-redis-fault.jsonl" \
./gradlew :apps:commerce-api:test --tests '*CouponLocalRedisFaultIntegrationTest' --rerun --no-build-cache
```

- 명시 실행은 위 재실행·캐시 비활성 옵션으로 수행한다. Gradle 작업이 실제 실행됐는지와 현재 실행의 테스트 보고서에서 Q9·Q10 두 사례가 모두 실행됐는지 확인한다. UP-TO-DATE·FROM-CACHE·비활성·0건·skip 결과는 이번 실행 통과로 기록하지 않는다. 실행 명령·시작/종료 시각·보고서 위치를 함께 남긴다.
- 테스트 클래스는 전용 test 패키지 `com.loopers.verification.localredis`에 두고 static nested 부트스트랩을 `@SpringBootTest(classes=...)`로 직접 지정한다. 부트스트랩은 `@SpringBootConfiguration`, `@EnableAutoConfiguration`, `@ConfigurationPropertiesScan("com.loopers")`를 사용한다. `@ComponentScan("com.loopers")`은 CUSTOM 필터 TypeExcludeFilter·AutoConfigurationExcludeFilter와 RedisTestContainersConfig·CommerceApiApplication 제외 필터를 함께 둔다. nested 구성이 일반 제품 스캔에 등록되거나 다른 기능 테스트의 기동 원본으로 선택되지 않도록 확인한다.
- RedisTestContainersConfig는 새 Redis 기동과 주소 덮어쓰기를 수행하므로 위 제외를 적용한다. CommerceApiApplication 제외는 재귀 스캔을 막으며 기존 `@ConfigurationPropertiesScan` 역할을 위 속성 스캔으로 보존한다. TypeExcludeFilter는 PaymentTxTestConfig 같은 테스트 전용 구성이 제품 빈을 바꾸지 않게 한다. 시작 시 RedisTestContainersConfig 빈 부재와 실제 앱 Redis endpoint를 검사한다. 실제 master/replica/database를 테스트 속성으로 주입해 기존 fixture의 System property보다 우선 적용한다. 기존 MySQL fixture와 기능별 테스트는 해당 구성을 유지한다.
- 컨테이너는 실제 master의 host 포트 바인딩·Docker 컨테이너 ID와 Compose 서비스 정보를 대조해 식별한다. 대상이 유일하고 예상한 실행 중 Redis임을 확인한 후 pause/unpause한다. 식별 불가·다른 대상·Docker로 제어할 수 없는 실행은 시작 조건 실패로 기록한다. 테스트 코드에서 사용 중인 master 서버의 INFO server 응답도 기록한다.
- 장애 조작은 확인한 기존 컨테이너의 **pause → 실제 Redis 호출 실패/timeout 확인 → unpause**로 한다. 각 검증의 finally에서 unpause와 서버 작업 종료 확인을 수행한다. 실험 대상 Redis는 실행 전후에 계속 사용 가능한 상태로 복구한다. Q9·Q10은 위 로컬 Redis 컨텍스트와 확인한 동일 컨테이너를 사용한다. Q7 등 실제 Redis 수용 검증에는 기존 자동 테스트 fixture를 사용할 수 있다.
- Q9·Q10 동안 상품 상세 조회·캐시 채움·무효화 등 같은 master 연결을 쓰는 경로도 멈춘다. 특히 사전 발급과 CLIENT LIST 관찰 사이에는 테스트가 의도한 명령만 사용해 마지막 명령 관찰을 보존한다.
- 공유 컨테이너를 건드리는 동안 다른 Redis 테스트·프로세스가 접근하지 않도록 직렬 실행한다. Gradle test worker·JUnit 병렬 설정·별도 test 작업/수동 실행의 중첩을 함께 확인한다. 기존 maxParallelForks=1만으로 다른 프로세스까지 격리됐다고 쓰지 않는다. 이 전제를 확인할 수 없으면 장애 검증을 시작하지 않는다.
- Q9·Q10 각각의 시작은 공통 준비에서 닫기 → 닫힘 확인 → 실제 master Set 0개 확인 → 필요한 데이터 준비·commit → 명시 열기 순서로 수행한다. Spring context의 이전 사례 상태에 의존하지 않으며 두 메서드의 실행 순서를 지정하지 않는다. 기존 점유가 있으면 시작 조건 실패로 기록하고 아래 사후 정리 절차로 확인한다.
- 각 실험의 try/finally에서 unpause·barrier 해제를 시도하고, 시작한 서버 작업과 DB TX가 끝난 뒤 자기 token만 정리한다. 정상 종료 시 master Set 0개와 §8.3 또는 아래 Q9의 늦은 명령 조건을 확인한다. 실패해 정상 정리 근거가 부족하면 실패 상태와 실행 기록을 보존하고 아래 사후 수동 정리 절차를 사용한다.
- test 소스의 획득 저장소 decorator는 실제 Redis 획득 명령을 보내기 전에 실행 기록 파일에 endpoint·database·키·자기 token·사용자/캠페인·단계를 남긴다. 기록에는 실행 시각·테스트 JVM PID/시작 시각, 관찰한 Redis run_id/클라이언트 ID, 사용한 MySQL 테스트 컨테이너 ID/접속 주소와 DB 완료·결과 근거를 연결한다. 미확인 항목은 불명으로 남긴다.
- 기록 파일은 위 명령처럼 저장소 루트에서 절대 경로를 `COUPON_LOCAL_REDIS_FAULT_LOG`로 전달한다. 기본 사용 위치는 로컬 전용 `docs/week8/verification/coupon-local-redis-fault.jsonl`이며 Gradle의 build/·테스트 결과 디렉터리와 분리한다. 테스트는 시작 시 환경변수의 절대 경로와 쓰기 가능 여부를 확인한다. 환경변수가 누락되거나 상대 경로이면 장치 준비 실패로 처리한다. 파일이 없으면 생성하고 기존 파일은 덮어쓰지 않는다. 실제 절대 경로를 실행/정리 기록에 남기고 append로 누적한다. 다음 test·재실행·clean이 이 파일을 덮어쓰거나 삭제하지 않게 한다.
- 각 이벤트는 UTF-8 JSON 한 줄로 파일에 추가하고 flush를 완료한 뒤 다음 단계로 진행한다. 획득 전 기록의 쓰기/flush가 실패하면 Redis 명령을 위임하지 않고 테스트 장치 실패로 기록한다. endpoint와 소유 token을 기록한 사실만으로 검증 통과를 판정하지 않는다. 이 보존 범위는 테스트 JVM의 비정상 종료와 Gradle 재실행/clean이며 머신·파일시스템 유실 복구는 주장하지 않는다. 제품 경로에 새 공유 실행 저장소를 추가하지 않는다.

Q9의 검증·복구 순서는 다음과 같다.

1. 다른 Redis 사용 작업을 멈춘 통제된 실행에서 대상 앱·DB·master endpoint·database·Set 키를 확인한다. 공통 준비에 따라 닫힘·빈 Set·데이터 준비를 확인하고 명시 열기를 마친다. 아래 master 관찰 경로로 INFO server의 `run_id`와 CLIENT ID를 받아 `beforeRunId`·`beforeId`로 보관한다.
2. 실제 Lua와 정상 반환을 사용하는 사전 발급 요청을 한 번 완료한다. 별도의 조회 연결에서 `CLIENT LIST ID <beforeId>`의 마지막 명령이 그 정상 반환의 `srem`인지 대조해 실제 명령 경로를 확인한다. 이 단계와 대상 Set의 빈 상태 확인은 기존 master 연결의 마지막 명령을 바꾸지 않는 관찰 연결에서 수행한다. 발급 효과는 준비된 테스트 데이터의 기대 결과에 포함한다.
3. 새로운 미발급 사용자를 준비·commit하고 수용이 열려 있음을 확인한 뒤 해당 컨테이너를 pause한다. 해당 사용자의 요청에서 실제 획득 실패·503·DB 발급 미진입·추가 발급 효과 0·수용 닫힘을 확인한다. 기존 쿠폰 POST 결과와 본인 목록은 같은 DB에서 조회한다.
4. 관련 서버 요청·DB 작업이 모두 끝났는지 확인하고 unpause한다. 신규 발급은 계속 닫아 둔다.
5. 1번과 같은 master 관찰 경로로 후속 CLIENT ID와 INFO server 응답을 받는다. `afterId == beforeId`와 `afterRunId == beforeRunId`를 모두 확인한다. 종료된 획득 호출 이후의 후속 응답을 동일 서버 프로세스·동일 연결에서 받았다는 사실을 선행 명령이 지나온 경계로 사용한다.
6. 해당 실패 실행의 자기 token만 SREM/cleanup하고 실제 master에서 Set 0개를 확인한다. 수용 닫힘을 유지한 채 검증을 마치고 Redis는 동작 가능한 상태로 둔다.

master 관찰은 비파이프라인·비Redis트랜잭션 상태의 `redisTemplateMaster.execute(RedisCallback)`에서 수행한다. callback이 받은 Lettuce native 명령 객체의 `clientId()`와 `info("server")`를 사용해 각각 Long·문자열 응답을 유한한 대기 한도로 받는다. 타입과 연결 경로를 확인하며 product Redis 포트에 새 관찰 API를 추가하지 않는다. 실제 Lua·SREM도 같은 팩토리의 공유 native master 경로를 사용한다는 전제를 사전 발급과 CLIENT LIST 관찰로 대조한다.

복구 경계 판정에는 위 경로의 후속 응답을 사용한다. 별도 CLI나 새 연결은 2번의 경로 관찰과 Set 조회에만 사용한다. run_id 또는 연결 ID 변화·연결 재생성·서버 재시작·경로 불일치·확인 명령 실패는 닫힘 유지·검증 실패로 기록한다. 단순 대기나 별도 연결의 PONG·빈 Set으로 이 확인을 대신하지 않는다.

이 절차는 알려진 요청 하나의 획득 실패와 pause/unpause를 통제하는 검증 조건이다. Q9 종료를 위한 별도 Gradle 작업·새 JVM·JUnit 전역 순서 설정·Redis 컨테이너 종료는 추가하지 않는다. 같은 실제 연결과 명령 순서 전제는 구현 단계에서 관찰해 검증한다.

#### 실패 실행의 사후 수동 정리

정상 종료 중 정리 근거를 확보하지 못한 Q9·Q10의 기록된 자기 token에는 다음 절차를 허용한다.

1. 실패한 테스트 JVM과 해당 Redis에 쿠폰 획득 명령을 보낼 수 있는 로컬 쿠폰 앱을 종료하고, 새 실행을 시작하지 않는다. 정리 기록에 대상 실행과 종료 확인을 남긴다.
2. 관련 DB 작업의 실제 종료와 쿠폰·발급 수 결과를 별도로 확인한다. JVM 종료·Redis 연결 부재·DB COUNT 일치만으로 DB 작업 종료를 대신하지 않는다. DB가 테스트 fixture 종료로 이미 폐기됐다면 그 DB 서버/자원의 종료 증거와 데이터 폐기 사실을 기록한다. 종료 근거가 없으면 정리를 보류한다.
3. 테스트 JVM 종료와 unpause 확인을 마친 뒤 정리용 연결을 새로 연다. 실제 master·database·키가 파일 기록과 일치하는지 확인하고, 이 연결을 유지하며 자기 CLIENT ID와 `CLIENT LIST TYPE NORMAL`을 조회한다. 정리용 연결 외의 일반 클라이언트가 모두 사라졌는지 확인한다. pause 중 또는 JVM 종료 전에 열어 둔 연결을 재사용하지 않는다. 기존 beforeId 하나의 부재만으로 재연결된 다른 클라이언트까지 종료됐다고 판단하지 않는다.
4. 1~3의 조건을 유지하며 파일 기록에서 소유권을 확인한 token만 master에서 SREM한다. 실제 응답·남은 자기 token과 `CLIENT LIST TYPE NORMAL`을 같은 정리용 연결에서 다시 조회한다. 추가 클라이언트가 관찰되거나 확인에 실패하면 정리 완료로 판정하지 않는다. 없는 token의 0도 결과로 기록하며 부분 실패 시 남은 대상만 다시 확인한다.
5. 실행 기록 파일의 실제 경로와 실행 종료·DB 종료·새 정리 연결 확인·삭제 대상/응답·Set/클라이언트 재조회 결과를 테스트 자원 정리 기록으로 남긴다. 사후 정리는 원래 실패를 통과로 바꾸지 않으며, 재검증은 위 명시 실행 명령으로 다시 수행한다.

정리 연결의 생성 시점과 전후 재확인은 통제된 로컬 환경의 관찰 조건이다. accept 대기열 FIFO만으로 모든 늦은 명령의 종료를 보장한다고 설명하지 않는다.

기록이 없거나 대상·종료·연결·명령 결과를 확인할 수 없으면 삭제와 재검증을 보류한다. 이 절차는 테스트가 소유한 점유의 사후 정리이며, 일반 제품의 재시작 후 복구나 자동 수용 재개로 확대하지 않는다. 제품 수동 재개는 §8.3의 조건을 따른다.

장애·경쟁의 위치는 test 소스의 Repository decorator로 통제한다. 제품 코드에 전용 테스트 hook·전역 실행 추적을 추가하지 않는다. decorator는 실제 저장소/Redis 호출을 위임하며 관찰 지점과 유한한 barrier만 제공한다.

| 검증 | 고정 관찰·제어 지점 |
|---|---|
| Q3 동시 중복 | 실제 ACQUIRED 반환 직후·발급 TX 전에서 별도 token을 가진 요청들을 유지해 사전 조회 통과를 확인. 잠금 대기/해제와 실제 DB 효과를 대조 |
| Q5 증가 뒤 INSERT 실패 | reserveOne 실제 1건 뒤 save 지점에서 실패 주입. 실제 MySQL rollback과 증가분/쿠폰 효과 0을 확인 |
| Q7 C=3 | 실제 Lua ACQUIRED 직후·writeTemplate 전 세 요청 유지. 네 번째 FULL·429와 DB 발급 미진입을 관찰. 일부 요청을 해제해 실제 반환 뒤 재수용 |
| Q9 획득 장애 | 현재 로컬 master에서 beforeRunId/beforeId와 정상 발급·반환 경로 확인 → pause/실제 발급 503·DB 미진입·기존 결과 조회 → 서버 종료·unpause → 동일 경로의 후속 CLIENT ID/INFO 응답과 두 ID 일치 → 자기 token 정리·Set 0개. 수용 닫힘 유지 |
| Q10 반환 장애 | Q9와 같은 명시 실행 로컬 Redis 컨텍스트를 사용. 실제 commit 완료 후 release 명령 위임 직전 decorator에서 확인한 기존 컨테이너를 pause. 실제 SREM 실패/timeout 뒤 201·DB 효과·닫힘 확인. unpause 후 §8.3의 조건에 맞춰 자기 점유만 정리하고 재개. 신규 요청 수용과 그 요청 종료 후 Set 0개 확인 |

timeout 뒤 EVAL/SREM이 늦게 실행될 수 있다. unpause 후에도 §8.3의 재개 판단을 적용한다. Q10은 명령의 종류와 자기 token에 미치는 효과를 확인하며 늦은 SREM의 정확한 완료 시각을 요구하지 않는다. barrier·컨테이너 제어·관찰 장치 실패는 실험 실패로 기록한다.

### 10.2 실제 DB 종료와 종료 불명 분기의 검증 구분

| 검증 층 | 방법·기대 | 확인하지 못하는 것 |
|---|---|---|
| 실제 MySQL | 정상 commit, INSERT 실패 rollback, 총량/중복 경쟁, 완료 통지와 DB 효과 대조 | 실제 DB 통신 단절의 모든 종료 상태 |
| 실제 HTTP timeout/연결 종료 | 쓰기 TX의 UPDATE 이후 지점을 테스트 decorator의 barrier로 유지. 클라이언트 종료 뒤에도 서버 작업은 살아 있고 token 조기 반환 없음. barrier 해제 후 실제 서버/TX 종료·DB 결과·정상 반환 확인 | client future 실패만으로 서버 종료를 증명하지 않음 |
| TX 관리자 테스트 대역 | template 콜백 진입 후 commit/rollback 실패에 UNKNOWN을 전달하고 예외 발생, 또는 진입 후 완료 통지 없이 실패. token 미반환·로그·닫힘·500·재개 보류 검증 | 실제 드라이버/네트워크가 UNKNOWN을 만드는 재현 성공 |
| TX 시작 실패 대역 | 선행 사전 조회는 정상 종료, 쓰기 콜백 전 연결 획득 실패를 재현. 업무 저장소 미호출·token 반환·500, 반환 정상이면 수용 유지. 후속 읽기 시작 실패에서도 앞선 rollback 확인을 유지 | 실제 풀 부하의 빈도·성능 |
| COMMITTED 뒤 예외 대역 | commit 확인+응답 candidate 있음이면 201·로그·token 반환, 결과 확보 없음이면 500·저장 효과 보존. 읽기/쓰기와 무관한 HTTP 전송 성공을 주장하지 않음 | 제품에 새 afterCommit 업무가 필요하다는 근거가 아님 |

test 소스에서 `CouponFacade`를 직접 생성하고 생성자에 `PlatformTransactionManager` 대역과 저장소 대역을 전달한다. Facade는 전달받은 관리자에서 읽기·쓰기 template을 만든다. 대역은 TX 동기화를 활성화해 afterCompletion 등록을 정상 수락하며 테스트는 등록 성공을 먼저 확인한다. 그 뒤 UNKNOWN 통지 또는 통지 생략을 의도적으로 수행하고, 종료 시 테스트 스레드의 동기화 상태를 정리한다. 대역은 실제 DB TX를 열지 않고 쓰기 콜백 진입 후 `UNKNOWN`을 통지하고 예외를 던지는 경우와 완료 통지 없이 예외를 던지는 경우를 각각 제공한다. 실제 MySQL 검증은 앱의 기존 TX 관리자를 사용한다. UNKNOWN 뒤 후속 조회 성공·다음 호출 미시작이 앞선 불명을 지우지 않는지도 검사한다.

실제 commit/rollback 관찰과 대역 분기 검증을 별도 결과로 남긴다. DB 세션 KILL로 UNKNOWN이 반드시 발생한다고 가정하거나 실행하지 않은 통신 단절을 통과로 쓰지 않는다. 모든 표는 구현·실행 전 검증 설계이며 현재 통과 결과가 아니다.
