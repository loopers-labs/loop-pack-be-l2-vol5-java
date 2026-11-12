# 쿠폰 발급·수용량 제어 상세설계

## 1. 상태와 읽는 기준

이 문서는 쿠폰 발급·보유 목록·동시 수용 제한·수동 장애 재개의 제품 계약과 상세 구조를 정의한다. 2026-11-11 사용자가 기본 설계를 최종 승인했고, 구현 1~5단계와 최종 전체 검사 결과는 [쿠폰 실행 기록](../verification/coupons.md)을 따른다. §12의 대기열·실행 자리 통합/등록 입구 제한은 구현·정상 전체 검사 완료이며, §13 캐시는 최종 승인 후 구현 전이다. §11의 발급 POST 제한은 변경 전 미구현 구안이며 현재 확장 구현 기준이 아니다. 정책 합의·문서 반영과 전체 상세설계 완료·최종 승인·구현·실행 검증은 구분한다.

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

§2~§10은 1차 기본 구현의 계약과 검증 기준이다. 추가 설계에서는 §12에 따라 대기 등록 입구의 요청률 제한과 대기/입장/실행 자리를 통합하며, 기존 발급 POST의 별도 요청률 제한·세마포어 이중 획득을 도입하지 않는다. 추가 설계가 완성되기 전에는 기본 구현 코드가 이 방식으로 바뀐 것으로 해석하지 않는다.

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

1차 기본 구현의 우선순위는 사용자 식별 → 기존 쿠폰 사전 조회 → 신규 수용 → 발급 TX의 캠페인 판단이다. 없는/비활성 캠페인도 수용 거절이면 429·503이 먼저일 수 있다. 기존 쿠폰은 비활성·품절보다 우선하고, 쿠폰이 없을 때 비활성이 품절보다 우선한다. 확장 시 입장 권한 검증·기존 결과 조회의 세부 순서는 §12에서 상세설계하며, 변경 전 §11의 발급 POST 요청률 우선 규칙은 적용하지 않는다.

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

**코드 확인:** modules/redis는 기본 REPLICA_PREFERRED와 별도 MASTER 연결을 제공한다. `redisTemplateMaster`는 `RedisTemplate<String, String>`이고 key/value/hash serializer는 String이다. 앱의 connect/command timeout은 각각 200ms이며 실제 장애 시 대기 시간 실측값이나 HTTP 전체 deadline이 아니다. local 설정은 master localhost:6379·replica localhost:6380이다. 기존 Redis 테스트 fixture는 생성자에서 컨테이너 주소를 System property에 설정하지만, RedisProperties가 먼저 바인딩된 컨텍스트에는 반영되지 않을 수 있다. 쿠폰 수용 통합 테스트는 컨텍스트 생성 전 동적 속성으로 fixture 주소를 주입하고, 쓰기 전에 주소·DB·master 설정과 컨테이너/연결 서버의 run_id 일치를 확인한다. 일반 컨텍스트의 연결 대상을 fixture 존재만으로 추정하지 않는다. 실제 관찰과 한계는 [쿠폰 실행 기록](../verification/coupons.md)의 2단계 후속 기록을 따른다.

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

## 10. 기본 기능 검증 기준

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

Q4는 충분한 Redis 용량 또는 DB 발급 callback 직접 경쟁으로 DB 불변식을 분리 검증한다. Q7은 mock·thread-local count·네 요청 단순 동시 전송으로 대체하지 않는다. barrier 실패 시 해제·종료 확인을 수행하며 살아 있는 작업과 겹쳐 DB/Redis를 지우지 않는다. 기존 RedisCleanUp의 FLUSHALL이나 관계없는 cache·컨테이너 전체 삭제를 사용하지 않는다. 확장 후 Q1~Q16 중 DB 불변식 등 유효한 검증은 유지하고, 수용/반환 경로 변경의 대체 검증은 §12의 상세설계에서 정한다. 변경 전 §11.4를 확장 완료 기준으로 사용하지 않는다.

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

실제 commit/rollback 관찰과 대역 분기 검증을 별도 결과로 남긴다. DB 세션 KILL로 UNKNOWN이 반드시 발생한다고 가정하거나 실행하지 않은 통신 단절을 통과로 쓰지 않는다. 이 표는 검증 기준이며 실제 통과 여부는 실행 기록을 따른다.

## 11. 발급 POST의 앱 인스턴스별 요청률 제한

**변경 전 미구현 구안:** 이 절은 발급 POST 앞의 제한을 검토하던 당시 기록이다. 이후 사용자 승인으로 대기열·실행 자리 통합과 대기 등록 입구 제한(§12)으로 변경했다. 아래 클래스/bean 이름·의사코드·RL3~RL6·검사 범위는 현재 확장의 구현 지시가 아니며 새 상세설계에서 대체한다. 기본값·설정 검증·라이브러리 시간 제어 근거 중 계속 유효한 부분은 §12.3에서 재사용한다. 구안은 구현·실행 검증되지 않았다.

### 11.1 정책과 적용 경계

**합의(2026-11-12):** Resilience4j RateLimiter 하나로 각 앱 인스턴스에 도착하는 쿠폰 발급 POST의 요청률을 제한한다. 사용자 연타와 순간 집중 요청이 사용자 존재 조회·쿠폰 사전 조회·Redis 수용 판단까지 반복 진입하는 비용을 줄이는 목적이다. 사용자별 한도는 두지 않으며 사용자·캠페인별 limiter를 생성하지 않는다.

| 항목 | 계약 |
|---|---|
| 대상 | `POST /api/v1/coupon-campaigns/{campaignId}/issues`만 적용. 모든 사용자·캠페인이 인스턴스 안의 같은 limiter 공유 |
| 초기값 | 갱신 주기 1초, 주기당 허가 10개. 테스트하기 쉬운 초기값이며 적정 운영 TPS의 실측값이 아님 |
| 대기 | `timeoutDuration=Duration.ZERO` 고정. 허가 없음은 즉시 거절, 자동 재시도·대기열 없음 |
| 거절 | `429 COUPON_RATE_LIMITED`, 기본 메시지 `쿠폰 발급 요청이 너무 많습니다. 잠시 후 다시 시도해주세요.`. 기존 ApiResponse 실패 형식 사용 |
| 실행 위치 | MVC 매핑 후 사용자 식별과 인자 변환 전. User 존재 DB 조회·쿠폰 사전 조회·Redis 획득·발급 TX보다 먼저 실행 |
| 제외 | 쿠폰 목록 GET·수용 상태/닫기/정리/열기 API·다른 기능 API·Facade 직접 호출 |
| 기존 결과 재요청 | 같은 요청률 제한 적용. 제한 통과 후 기존 결과 200·같은 couponId 계약 유지 |

허가를 얻은 HTTP 요청은 뒤에서 입력 오류·사용자 없음·품절·수용 닫힘·Redis FULL·DB 오류가 나도 그 주기의 허가를 돌려주지 않는다. 정상 종료 때 반환하는 Redis token과 다른 수명이다. 거절 요청은 DB·Redis 부작용 없이 종료하며, 수용 상태를 닫거나 기존 token을 정리하지 않는다. 응답 필드·Retry-After 헤더·재시도 기능을 새로 추가하지 않는다.

발급 handler에 매핑된 요청은 헤더 누락·잘못된 숫자 입력도 한도 초과 시 요청률 429가 먼저다. 한도가 남으면 기존 400/404 매핑을 유지한다. 매핑 자체가 실패한 잘못된 경로·HTTP 메서드는 기존 MVC 처리를 유지한다. GET의 식별·목록 오류 우선순위는 바꾸지 않는다.

주기 경계 전후로 요청이 몰릴 수 있으므로 임의의 연속 1초 구간에서 반드시 10건 이하라는 보장은 하지 않는다. 여러 인스턴스는 각자 제한하며 전체 앱 군집의 합계 10건/초·사용자별 공정성·재시작 전후 허가 잔량 보존을 보장하지 않는다. Redis 동시 수용 C=3·DB 총량/중복 보호·token 반환·수동 재개 규칙은 유지한다. Bulkhead·Redisson·캐시어사이드·로컬 캐시는 이번 추가 범위가 아니다.

### 11.2 설정·클래스·메서드 구체화안

새 제품 파일은 다음 세 책임으로 제한한다. 단순 전달용 서비스·저장소 포트·별도 실행 풀·사용자 Map을 만들지 않는다. 기존 Resilience4j 의존성을 사용하며 버전 변경은 하지 않는다.

| 타입·위치 | 계약 |
|---|---|
| `config/CouponRateLimitProperties` | `commerce.coupon.rate-limit.limit-for-period` 기본 10, `commerce.coupon.rate-limit.refresh-period` 기본 `1s`. 기존 속성 스캔으로 바인딩. Properties는 양의 int·양의 Duration을 검증하며, 형식 오류는 바인딩 실패. 라이브러리 표현 범위는 interceptor 생성 시 RateLimiterConfig 검증에 맡기고 표현 불가는 기동 실패 |
| `interfaces/api/coupon/CouponIssueRateLimitInterceptor` | `@Component`로 등록하는 singleton이며 bean 이름은 `couponIssueRateLimitInterceptor`. 생성 시 위 설정과 timeout 0으로 `RateLimiter.of("couponIssue", config)` 한 번 생성·보관. `preHandle`에서 대상 발급 handler인지 확인한 뒤 `acquirePermission()` 한 번 실행. 실패하면 CoreException의 새 ErrorType 발생. UserFacade·CouponFacade·DB·Redis 의존 없음 |
| `config/CouponRateLimitConfig` | `@Configuration`의 bean 이름은 `couponRateLimitConfig`. interceptor를 생성자 주입받는 별도 WebMvcConfigurer로 위 interceptor 등록. 발급 경로로 범위를 좁히고 interceptor도 POST·CouponV1Controller.issue handler 여부 확인. 기존 WebMvcConfig의 요청자 resolver 등록은 유지 |

설정은 모든 프로필에 같은 기본값을 사용하고 앱 시작 시 읽는다. 값 변경은 시작 옵션/설정과 재시작으로 적용하며 동적 갱신 API·활성화 스위치는 두지 않는다. timeout은 외부 변경 항목으로 노출하지 않는다. Controller/Facade에 `@RateLimiter`를 붙이는 방식은 인자 resolver의 DB 조회보다 늦으므로 채택하지 않는다.

W6 준비·O11의 W7 기동에는 기존 CouponBeanIsolation 방식대로 새 동작 bean `couponIssueRateLimitInterceptor`, `couponRateLimitConfig`를 정확한 이름으로 제외한다. 동작 없는 CouponRateLimitProperties는 남긴다. 일반 앱/테스트의 등록과 기존 관리 타입·테이블·행 보존 기대값은 유지한다. 기존 WebMvcConfig가 제외 대상 bean을 생성자 주입하지 않도록 분리한다.

### 11.3 주요 처리 의사코드

```text
CouponRateLimitProperties 생성:
  limitForPeriod <= 0 또는 refreshPeriod <= 0이면 기동 실패
  형식 오류는 속성 바인딩 실패
interceptor 생성(컨텍스트당 한 번):
  RateLimiterConfig 생성 시 라이브러리 표현 범위 검증, 표현 불가면 기동 실패
  limiter = RateLimiter.of("couponIssue", 설정값 + timeoutDuration=0)
preHandle(request, response, handler):
  POST의 CouponV1Controller.issue handler가 아니면 true
  limiter.acquirePermission() == false 이면
    CoreException(COUPON_RATE_LIMITED) 발생
    // 기존 ApiControllerAdvice가 429 ApiResponse 생성, 후속 체인 미진입
  true 반환
  // 이후 기존 resolver → Controller → Facade → DB/Redis 흐름 진행
  // 요청 성공/실패 뒤 RateLimiter 허가 반환 없음
```

예상하지 않은 limiter 오류를 허가 성공·Redis FULL로 바꾸거나 DB 경로로 우회하지 않는다. 기존 내부 오류 응답을 따르며, Redis 수용 상태를 변경하지 않는다. 요청마다 limiter를 재생성하거나 캠페인 ID를 limiter 이름으로 쓰지 않는다.

### 11.4 검증·완료 기준 — 추가 기능 실행 전

| ID | 기대값·관찰 |
|---|---|
| RL1 | 옵션 생략은 10개/1초/대기 0. 시작 설정으로 개수·주기를 바꿀 수 있고, 0·음수·형식 오류·표현 불가 설정은 기동 실패 |
| RL2 | 실제 Resilience4j에서 같은 주기의 허가 소진 후 즉시 거절, 다음 주기 재허용. 동시 호출도 공유 한도를 초과하지 않음. 주기 경계와 허가 시각을 통제해 테스트 자체의 경계 통과를 오판하지 않음 |
| RL3 | 실제 MVC 체인에서 거절 시 429·COUPON_RATE_LIMITED·ApiResponse 형식. UserFacade/User 존재 조회·CouponFacade·Redis 호출 없음. Controller 직접 호출만으로 순서 검증을 대체하지 않음 |
| RL4 | 서로 다른 사용자·캠페인도 한도를 공유. 기존 쿠폰 보유자의 POST도 초과 시 429. 제한 통과한 최초 201/기존 200 및 기존 입력 오류 유지 |
| RL5 | 한도 소진 중에도 본인 목록·수용 제어·다른 API는 제한되지 않음. COUPON_ADMISSION_FULL과 새 오류를 구분하고, 요청률 거절에 token·DB·수용 상태 변화 없음 |
| RL6 | 기존 쿠폰 관련 테스트·Checkstyle·ArchUnit. 격리 목록 변경에 해당하는 W6 준비 및 O11 조립 검증. 실행 수·실패·skip·한계와 이전 결과를 구분해 기록 |

기존 업무 테스트 중 한 컨텍스트의 발급 POST 총량이 기본 한도를 넘을 수 있는 클래스(현재 `CouponApiE2ETest`)는 실행 전에 해당 컨텍스트 속성으로 충분한 한도를 지정한다. 한도는 그 컨텍스트에서 발생시키는 발급 POST 전체를 수용하도록 잡고 사용자 식별 오류 요청도 포함한다. 실행 타이밍에 따라 429가 관찰될 때만 설정을 바꾸는 방식은 쓰지 않는다. 총량이 기본 한도 이하인 HTTP 장애·로컬 Redis 장애 컨텍스트는 한도 조정을 위해 수정하지 않는다. 기대하는 200/201·Redis 429/503·DB 수량은 완화하지 않는다. 요청률 테스트는 기본값 및 명시한 낮은 한도를 별도로 검증하고, 공통 test 프로필에서 기능을 일괄 끄지 않는다. 새 테스트도 공유 limiter 잔량이나 실행 순서에 의존하지 않게 격리한다.

RL2의 시간·실행 제어는 다음과 같이 구분한다.

- 실제 interceptor를 직접 생성하는 단위 테스트에서 소진·공유·동시 호출을 검증한다. 충분히 긴 주기(예: 1시간)를 쓰고, 생성 직전부터 모든 호출 종료까지 단조 시계로 잰 시간이 그 주기 미만인지 확인한다. Resilience4j 2.2.0 AtomicRateLimiter는 인스턴스 생성 시각을 기준으로 주기를 계산하므로 이 조건에서 최초 주기 안의 허가 수를 대조한다. 시간 전제를 벗어나면 검증 실패로 남기며 skip·기대값 완화로 통과시키지 않는다.
- 재허용은 별도 인스턴스·짧은 주기로 검증한다. 먼저 허가 소진과 거절을 확인한 뒤, 유한한 기한과 재조회 간격으로 다음 허가를 확인한다. 고정 sleep 한 번 뒤의 성공만으로 소진·거절 검증을 대신하지 않는다. 제품에 시계 주입·테스트 hook·새 공개 관찰 API를 추가하지 않는다.
- RL1의 대기 0은 생성된 limiter의 설정을 test 소스에서 확인하고, 긴 갱신 주기의 소진 뒤 호출이 다음 갱신을 기다리지 않고 거절되는지도 유한한 기한으로 검증한다. 스케줄링 지연을 업무 대기로 오판하는 극단적으로 짧은 성능 상한을 두지 않는다.
- RL3~RL5는 낮은 한도·충분히 긴 주기의 전용 HTTP 컨텍스트에서 검증한다. 컨텍스트 종료 시 자원을 정리하고 다른 테스트의 허가 잔량에 의존하지 않는다.

구현 단위의 관련 검사 제안은 신규 설정/interceptor/HTTP 테스트, 기존 `CouponApiE2ETest`·`CouponHttpFaultE2ETest`, W6 준비·O11 조립, Checkstyle·ArchUnit이다. 소스가 그대로인 HTTP 테스트도 새 interceptor가 실제 체인에 들어가므로 실행 대상에서 자동 제외하지 않는다. 공유 로컬 Redis pause·전체 회귀·추가 성능 실험은 별도 착수 범위이며, 재실행하지 않은 장애 사례는 과거 기본 구현 결과와 추가 기능의 실행 근거를 구분한다. 이 절은 검증 계획이며 코드·검사 실행 승인이 아니다.

**확인 근거:** Resilience4j 2.2.0의 RateLimiter `of`·`acquirePermission`, RateLimiterConfig의 `limitForPeriod`·`limitRefreshPeriod`·`timeoutDuration`, 공식 RateLimiter 문서의 주기별 허가 설명. Spring Framework 6.2.5 DispatcherServlet의 `doDispatch`는 `applyPreHandle` 뒤 HandlerAdapter를 실행한다. 로컬 Resilience4j 2.2.0 sources의 AtomicRateLimiter 생성자·currentNanoTime에서 인스턴스별 시간 기준도 읽기 확인했다. 이는 배치·검증 방법 선택의 문서/소스 근거이며 이번 기능의 실행 검증은 아니다.


## 12. 입장 대기실 추가 설계 — 정상 범위 승인 설계

이 절은 2026-11-12 세 차례 Claude 정적 검토와 보완을 거쳐 사용자가 정상 범위 전체 설계를 최종 승인한 기준이다. 마지막 검토는 필수 수정·새 사용자 판단·정상 구현 전 blocker 없음으로 보고됐고 S6/S7은 계약 변경 없는 문구 보완으로 반영했다. 코드 구현 착수·실행 검증·보류한 장애 복구까지 승인/완료된 것은 아니다. §2의 대기실 제외는 기본 구현 당시 범위다. 추가 설계가 완성·승인되기 전에는 기존 발급 경로를 바꾸지 않는다.

### 12.1 합의한 사용자 경험과 보장 범위

- 추가 설계·구현·검증의 대상은 단일 앱 인스턴스로 한정한다. 다중 인스턴스의 입장 처리 조율·자동 장애 인계는 범위 밖이다. 단일 인스턴스의 재시작·Redis 장애 처리는 상세설계 대상에 남는다.
- 대기 상태 조회 API를 제공한다. 화면·클라이언트 자동 폴링·푸시 알림 구현은 제외하며 수동 API 호출로 상태를 확인할 수 있어야 한다.
- 대기열과 실행 자리 관리를 통합한다. 입장 허용 시 실행 자리를 확보하고 발급 시작 시 그 자리를 그대로 사용한다. 발급 POST에서 기존 세마포어를 별도로 다시 획득하지 않는다. 이는 앞선 “기존 세마포어 그대로 유지” 방향을 대체한다. 기존 DB 종료 확인 전 반환 금지·종료 불명 시 자리 유지·장애 시 신규 진입 차단의 안전 원칙은 보존하며 새 상태와 복구 절차로 상세화한다.

- 동시에 신규 발급을 운영하는 캠페인은 하나라는 운영 범위 제약을 둔다. 대기 항목은 해당 캠페인에 귀속시키되 캠페인 간 공통 자리 배분·공정성은 설계/검증 대상에서 제외한다. 여러 캠페인의 동시 운영을 자동 차단하는 DB 제약·관리 기능은 추가하지 않는다.
- DB에 과거 캠페인과 보유 쿠폰은 남을 수 있고 보유 목록·기존 발급 결과 조회는 유지한다. 캠페인 하나만 저장하거나 전체 DB에서 ACTIVE 캠페인 하나만 허용한다는 계약은 아니다.
- 대기 상태를 보여주고 차례가 되면 발급 시도 기회를 준다. 서버가 접수 후 자동 발급하는 비동기 작업 큐는 선택하지 않았다.
- 입장 허용은 쿠폰 수량 예약·발급 성공 보장이 아니다. 실제 발급의 총량·사용자 중복 보호는 DB에 남고 입장 후에도 품절될 수 있다.
- 대기 순서대로 복수 인원에게 입장 기회를 준다. 한 사람의 발급 완료·이탈·만료를 기다린 뒤 다음 한 사람만 들이는 방식은 선택하지 않는다.
- 동일 사용자·동일 캠페인의 유효한 대기 항목이 있으면 재등록·새로고침·재접속·여러 탭에서도 기존 항목과 순번을 공유한다. 만료 후 다시 등록하면 새 순번으로 맨 뒤에 들어간다. 입장 권한의 유효기간은 아래 고정 기한 정책을 따른다. 입장 전 대기 항목은 시간 경과만으로 만료시키지 않는다. 접속 생존 확인·대기 기한 갱신 기능은 이번에 추가하지 않는다.
- 입장한 사용자 사이의 발급 경쟁을 허용한다. 앞서 입장한 사용자가 늦게 요청하면 뒤에 입장한 사용자가 먼저 발급받을 수 있다. 엄격한 발급 완료 순서를 보장하지 않는다.

선택 이유는 대기 상태·입장 순서를 제공하면서 수량 예약과 이탈자의 예약 회수 없이 기존 DB의 최종 발급 판단을 유지하기 위해서다. 한 사람씩 완료를 기다리는 방식의 앞 요청 지연 전파를 피하는 대신, 입장 후 품절과 입장 순서/발급 순서 차이를 허용한다.

캠페인별 귀속은 순번·권한·발급 대상을 연결하기 위해 유지한다. 현재는 동시 운영 캠페인을 하나로 좁혀 캠페인 간 자리 배분과 기아 정책 비용을 제외한다. 대신 다중 캠페인 동시 운영은 보장하지 않으며, 운영 캠페인 전환 시 이전 입장 권한·실행 점유·종료 불명 처리는 재시작/복구와 함께 후속 범위로 둔다. 기존 캠페인과 발급 기록을 지우는 방식으로 전환하지 않는다.

중복 등록에서 기존 순번을 유지하는 이유는 등록 응답 유실이나 새로고침에 따른 정상 재요청에 불이익을 주지 않으면서 한 사용자·캠페인이 여러 대기 자리를 차지하지 않게 하기 위해서다. 만료 후에는 순번을 보존하지 않고 새 대기 항목으로 취급한다.

단일 인스턴스로 좁혀 다중 인스턴스 조율·장애 인계 비용을 제외하고, 상태 조회 API로 입장 대기실의 관찰 가능성을 확보한다. 입장 후 다시 자리 부족·요청률 초과로 거절되는 사용자 경험을 피하기 위해 실행 자리를 입장 시 배정하고 입구 요청률 제한과 분리한다. 새 상태 전이·응답 유실·미사용 권한 만료와 실행 종료를 구분하는 복구 비용은 상세설계 대상에 남는다.

### 12.2 실행 자리 통합의 합의와 불변식

- 정상 수용 중 빈 실행 자리가 없으면 대기 항목을 유지하고 대기 상태를 반환한다. 자리 부족만으로 기존 COUPON_ADMISSION_FULL을 발급자에게 다시 반환하는 이중 경쟁 경로를 두지 않는다.
- 입장 가능 상태는 실행 자리 확보를 뜻한다. 상태는 개념적으로 대기 → 입장 가능 → 발급 진행 → 종료로 구분한다. 상태·저장 구조·클래스의 구체화는 §12.8~12.12를 따른다.
- 발급 실행을 시작한 뒤 실패해도 입장 권한을 복원하거나 자동 재시도하지 않는다. 실행 자리는 DB 종료를 확인한 뒤 반환하고, 종료 불명이면 유지한다. 실패 후에는 §12.6에 따라 사용자의 명시적 새 등록만 허용하며 자동 재등록을 추가하지 않는다.
- 같은 입장 권한으로 동시에 여러 요청이 와도 새 발급 실행은 하나만 시작해야 한다. 기존 DB 결과가 확인되면 그 결과를 반환하고, 아직 진행 중이면 추가 실행 없이 처리 중 상태를 반환한다. HTTP/응답은 §12.7·§12.11을 따른다.
- 입장 가능 인원 + 발급 진행 중인 실행 수는 실행 자리 상한 이하여야 한다. 종료 불명 실행은 진행 점유에서 임의로 빼지 않는다. 동시 운영 캠페인은 하나이므로 캠페인 간 배분은 하지 않는다.
- 입장 권한의 유효기간은 서버가 입장을 허용해 실행 자리를 배정한 시점부터 계산한다. 최초 상태 조회 시점이 아니다. 조회·재등록·발급 재요청으로 기한을 연장하지 않는다. 초기 유효기간은 30초다. 설정은 §12.10, 서버 시각/만료 경계는 §12.12를 따른다.
- 입장 후 30초는 발급 실행을 시작할 수 있는 기한이며 DB 처리 완료 기한이 아니다. 유효한 권한으로 서버가 발급 진행 상태로 전환한 뒤에는 입장 기한이 지나도 DB 종료 확인 전 자리를 회수하지 않는다. 클라이언트가 기한 전에 요청을 보냈다는 사실만으로 실행 시작을 보장하지 않는다.
- 미사용 입장 권한이 만료되면 해당 자리를 회수해 다음 대기자에게 배정할 수 있다. 재등록은 새 순번으로 맨 뒤다. 실행을 시작했으면 입장 허가 시간 경과·클라이언트 연결 종료만으로 반환하지 않고 DB 종료를 확인한다. 만료/실행 시작 경합에서 중복 반환·초과 실행을 막아야 한다.
- 자리가 실제로 반환된 만큼 대기 순서를 따라 복수 인원에게 입장 기회를 줄 수 있다. 주기마다 고정 N명을 빈자리와 무관하게 입장시키는 안은 채택하지 않는다. 입장 승격과 미사용 권한 회수는 §12.4의 서버 스케줄러가 담당한다. 초기 실행 간격은 1초다.
- 이 보장은 정상 수용과 유효한 권한의 실행 자리 보장이다. 품절·비활성·잘못된/만료된 권한·DB/Redis 장애를 성공으로 바꾸거나 장애 시 신규 발급을 우회한다는 뜻이 아니다.

관찰용 초기 설정은 실행 자리 상한 3개·스케줄러 실행 간격 1초·입장 권한 유효기간 30초로 한다. 사용자가 작고 관찰하기 쉬운 값을 Codex 추천에 위임했다. 기존 C=3을 이어받아 네 번째 사용자부터 대기를 확인하고, 미사용 권한 만료도 짧은 시간 안에 관찰할 수 있도록 정했다. 운영 적정값·성능 검증 결과가 아니며 설정 이름/변경 방식은 §12.10~12.11을 따른다. 기존 등록 RateLimiter의 1초/10개·대기 0은 유지한다. 입장 전 대기 항목의 만료 정책은 이 30초와 별개이며 이번에는 시간 만료를 두지 않는다.

화면·자동 폴링이 없는 현재 범위에서는 사용자가 상태 조회 API로 입장 가능 여부와 만료 시각을 확인한 뒤 발급 POST를 수동 호출한다. 조회 시각부터 30초를 새로 부여하지 않으므로 늦게 확인하면 남은 시간이 짧거나 이미 만료됐을 수 있다. 만료 시각의 필드는 §12.7의 expiresAt이고 표현은 §12.11을 따른다.

### 12.3 대기 등록 입구의 요청률 제한 — 정책 변경 합의

- §11의 발급 POST 제한을 대기 등록 입구로 옮긴다. Resilience4j·앱 인스턴스별 하나·사용자별/캠페인별 limiter 없음·기본 1초 주기 10개·대기 0·시작 설정 변경 가능 정책은 유지한다. 수치는 적정 처리량의 실측값이 아니다.
- 대기 등록 요청은 사용자 존재 DB 조회·대기 상태 저장소 접근보다 앞에서 허가를 판단한다. 초과 시 기존 추가 설계의 429 COUPON_RATE_LIMITED·ApiResponse 실패 형식을 사용하고 신규 대기 등록·순번 변경·자리 배정을 하지 않는다. 이미 있는 항목의 순번이나 권한도 변경하지 않는다.
- 허가는 시간 주기로 갱신하며 요청 결과에 따라 반환하지 않는다. 설정 바인딩/양수 검증·라이브러리 표현 범위의 기동 실패와 실제 라이브러리 소진/재허용 검증 방법은 §11의 재사용 가능한 근거를 따른다.
- 유효한 입장 권한의 발급 요청에는 이 RateLimiter를 다시 적용하지 않는다. 발급 시 별도 세마포어도 재획득하지 않으므로 입장 후 정상 요청이 요청률/자리 부족으로 다시 거절되지 않게 한다.
- 대기 등록/조회 경로는 각각 `POST /api/v1/coupon-campaigns/{campaignId}/waiting`, `GET /api/v1/coupon-campaigns/{campaignId}/waiting`으로 한다. 사용자는 queue-entry 대신 waiting 명명을 선택했다. 요청/응답·헤더·오류 코드는 §12.7을 따른다. handler는 CouponV1Controller의 registerWaiting/getWaiting, 새 타입/bean은 §12.11을 따른다. 오류 메시지는 오류 코드별 의미를 표현하되 코드/HTTP 계약을 바꾸지 않는다. §11의 발급 전용 interceptor 이름이나 W6/O11 목록을 그대로 구현하지 않는다.
- 조회 API·잘못된 권한의 발급 호출에 별도 limiter/격리를 추가하는 것은 이번 범위에 포함하지 않는다. 등록 제한만으로 조회·발급의 모든 선행 DB/Redis 비용이 보호된다고 주장하지 않는다. 발급 POST에서 기존 결과 반환은 입장 권한보다 우선하며 구체 흐름은 §12.6을 따른다.

추가 검증에 반드시 포함할 조건(아직 미실행): 등록 제한 초과 시 사용자/대기 저장소 미호출·기존 순번/권한 불변, 사용자별 limiter를 만들지 않는 등록 한도 공유, 등록 한도 소진 중에도 유효한 입장자의 발급이 요청률 거절 없이 실행, 입장 자리 상한·실패 뒤 권한 복원/자동 재시도 없음·중복 실행 방지·대기 중 시간 만료 없음·입장 허용 시각 기준 30초·조회/재요청에 의한 기한 연장 없음·30초 안에 시작한 DB 작업은 기한 경과로 회수하지 않음·만료/실행 시작 경합·DB 종료 불명 점유 유지, 기존 DB 총량/사용자 중복/commit 응답 유실 보존이다. 전체 테스트 ID·실행 범위와 W6/O11 조립 목록은 최종 상세설계에서 맞춘다. 과거 기본 구현 실행이나 §11의 미실행 구안을 새 검증 통과로 쓰지 않는다.

### 12.4 서버 스케줄러의 만료 회수·입장 배정 — 합의

- 스케줄러는 Java 앱에서 실행한다. Java의 주기 작업이 Redis 저장소를 통해 만료 회수·입장 배정 Lua를 호출하며 Redis 안에 별도 예약 스케줄러를 두지 않는다. 한 Lua 호출 안에서의 상태 변경과 Java의 반복 호출 주기를 구분한다.
- 단일 앱 인스턴스 안의 서버 스케줄러가 주기적으로 미사용 입장 권한의 만료를 회수하고 빈 실행 자리를 확인해 대기 순서대로 입장 권한을 배정한다. 화면·클라이언트 자동 폴링이 없어도 이 작업은 진행한다.
- 스케줄러는 실행 자리를 배정할 뿐 쿠폰 발급 TX를 시작하지 않는다. 실제 발급은 사용자의 발급 POST로 시작한다. 자동 비동기 발급 worker나 별도 실행 대기줄을 추가하지 않는다.
- 상태 조회 API의 책임은 본인 대기/입장 상태 조회다. 조회 횟수·접속 여부를 입장 승격의 실행 조건이나 입장 권한 기한 연장 조건으로 사용하지 않는다.
- 정상 수용 상태에서 미사용 만료 권한 회수 → 빈자리 계산 → 앞순번부터 빈자리 수 이내의 복수 입장 배정 순서를 따른다. 발급 진행/종료 불명 점유는 만료 회수 대상이 아니다. 회수·승격·발급 시작이 경합해도 중복 배정/반환 없이 §12.2 상한을 지켜야 한다.
- Java 주기 작업의 유스케이스명은 processAdmissions(입장 처리)다. 직전 작업 종료 후 1초를 기다리는 fixed delay로 반복하며 운영 캠페인 ID는 시작 설정으로 하나 지정한다. 매 실행에서 해당 캠페인의 DB 상태를 짧은 읽기 TX로 조회해 품절 여부를 전달한다. 한 번의 회수/배정은 상한 C 이내이며 시각은 §12.12의 Redis TIME을 사용한다. Spring lifecycle을 따르는 CouponWaitingScheduler가 내부 단일 실행기를 관리하고 컨텍스트 종료 뒤 새 작업을 예약하지 않는다. @EnableScheduling/@Scheduled·@Async·별도 발급 worker는 추가하지 않는다. 실행 중 작업을 포함한 종료 처리는 WQ11로 검증하고 장애별 재시작 상세는 후속 범위다. 이 절의 채택을 해당 실패/복구 흐름까지 완성한 것으로 해석하지 않는다.

추가 검증 필요 조건(아직 미실행): 조회 요청 없이 만료 회수와 다음 입장 배정 진행, 빈자리 0이면 추가 배정 없음, 미사용 만료/발급 시작 경합에서 상한 유지, 진행/불명 점유 미회수, 조회·재요청이 입장 기한에 영향 없음, 스케줄러가 쿠폰/발급 수를 변경하지 않음이다. 반복 실행의 멱등성과 실패 후 새 배정 조건도 상세 검증 기준에 포함한다.

### 12.5 Redis 상태 저장·Lua 경계와 장애 후속 범위 — 합의

- 대기 순번·입장 권한·실행 점유는 Redis에 저장한다. 앱 메모리만을 이 상태의 기준 저장소로 사용하는 안은 채택하지 않는다. 실제 발급 결과와 총량·사용자 중복 보호는 기존 DB가 기준이다.
- 기존 Redis 인프라를 사용하고 Redis 변경 작업을 네 Lua 경계로 묶는다. (1) 등록: 유효 항목 확인·재사용 또는 새 ID/순번 등록, (2) 스케줄러: 미사용 만료 회수·빈자리 계산·앞순번 입장 배정, (3) 실행 시작: ID/사용자/캠페인/상태/기한 검증·READY에서 PROCESSING 전환, (4) 실행 종료: DB 종료 확인을 전제로 결과 기록·자기 자리 반환. 실제 DB TX는 Lua 밖에서 실행하고 종료 불명 실행에는 정상 종료 반환을 수행하지 않는다.
- 상태 조회는 Redis 변경 작업과 구분한다. 자료구조·키와 네 작업의 개요는 §12.8, 입력/반환값·쓰기 전 검증·단순 전이 의사코드와 검증은 §12.11~12.13을 따른다.
- 이번 확장의 복구는 자동 복원 없이 순번 복원을 포기하는 수동 재시작으로 한정한다. Redis 오류·수용 상태 불명 시 신규 등록·입장 배정·발급 시작을 중단한다. 이미 시작한 작업의 DB 종료와 결과를 확인하며 종료 불명이 남으면 재개하지 않는다.
- 운영자가 위 확인을 마친 뒤 기존 대기 순번·미사용 입장 권한을 무효화하고 새 대기실을 시작한다. 사용자는 다시 등록한다. 앱 재시작에서도 이전 대기를 자동 복원하지 않고 같은 수동 재개 원칙을 따른다. 기존 쿠폰·발급 수는 DB에 보존한다.
- epoch/회차별 키 관리 방식은 이번에 채택하지 않고 보류한다. 이전 요청·늦은 Redis 명령·옛 권한과 새 대기실의 분리, 장애별 초기화/수동 재개 절차·실험은 정상 흐름 설계·구현 뒤 후속 작업으로 남긴다. 그 후속 작업 전에는 장애 복구·재시작 안전성까지 완성한 것으로 주장하지 않는다. 확인 없는 키 삭제·살아 있는 실행의 점유 회수는 허용하지 않는다.
- 순번 보존·자동 복구·장애 심화 검증은 사용자 요청으로 후속 개선에 남기고 이번 정상 흐름 설계를 우선한다. 이 범위 축소를 기존 기본 구현의 유효한 검증을 삭제/완화하거나 새 흐름의 중복 실행·만료 경합·상한 보호 검증을 생략할 승인으로 해석하지 않는다.
- Redis 접근이 다시 가능하다는 사실이나 시간 경과만으로 진행 중/종료 불명 실행 자리를 회수하지 않는 원칙은 유지한다. 어떤 간소화안을 선택해도 이미 발급된 DB 쿠폰과 발급 수를 되돌리지 않는다.

### 12.6 기존 결과·품절·중복 호출·실패 후 등록 — 합의

- **이미 발급받은 사용자의 발급 재요청:** 기존 사용자 식별 후 본인·캠페인의 저장된 쿠폰이 있으면 입장 권한 없이 기존 200·같은 couponId 결과를 반환한다. 대기실에 다시 줄 세우거나 새 실행 자리를 배정하지 않는다. 입장 권한이 없거나 만료됐더라도 기존 결과 반환을 막지 않는다. 응답 유실 후 재요청도 같은 원칙이다. 최초 발급 성공은 기존처럼 commit 확인 뒤 201이다.
- **품절과 남은 대기자:** 품절이 확인되면 새 입장 배정을 중단하고 남은 대기자의 상태 조회에 품절 종료를 표시한다. 이미 발급된 사용자의 결과 조회는 품절 종료보다 우선한다. 품절 snapshot/기록과 미사용 만료 회수·진행 점유 보존은 §12.9·§12.12를 따른다. 품절을 이유로 진행 중인 점유를 조기 반환하지 않는다.
- **같은 권한으로 진행 중 재요청:** 기존 DB 쿠폰이 아직 없고 해당 실행이 진행 중이면 추가 실행·쿠폰 INSERT 없이 처리 중 상태를 반환한다. 입장 기한을 연장하거나 새 자리를 만들지 않는다. 서버가 대신 다음 실행을 예약하지 않는다. HTTP 202·ApiResponse 성공 형식으로 처리 중 상태를 반환한다. 최초 실행 요청은 기존처럼 DB 결과까지 기다려 201/오류를 반환한다. 202는 두 번째 실행이나 후속 작업을 예약하지 않는다. 202의 데이터는 §12.7의 대기 응답으로 PROCESSING을 표시한다.
- **발급 실패 후 사용자의 새 대기 등록:** 권한 복원·자동 재시도·자동 재등록은 하지 않는다. 사용자가 직접 새 등록을 요청하면 신규 순번으로 맨 뒤에 등록할 수 있다. 다만 품절 등 발급 불가 상태나 장애로 닫힌 상태를 우회하지 않는다. 진행/종료 불명 실행은 실패가 확정된 것으로 간주하지 않고 기존 점유를 유지한다. 기존 쿠폰이 확인되면 새 발급 효과를 만들지 않는다.

추가 검증 필요 조건(아직 미실행): 기존 쿠폰은 입장 권한 없음/만료·품절 뒤에도 같은 결과 반환, 응답 유실 후 재대기/추가 효과 없음, 품절 뒤 신규 입장 중단·남은 대기자의 품절 종료, 같은 권한의 동시 요청이 한 실행만 시작하고 나머지는 처리 중 반환, 실패 후 권한 자동 복원 없음·사용자 새 등록은 맨 뒤·품절/닫힘 우회 없음이다. API 우선순위·전이·검증은 §12.11~12.13을 따른다.

대기 등록 시 서버가 생성한 `waitingId`를 반환하고 유효한 중복 등록에는 같은 ID를 반환한다. 사용자는 발급 POST의 요청 헤더로 이 ID를 전달한다. 서버는 사용자·캠페인·현재 대기 항목 ID·상태·유효시간을 대조하고 입장 가능 → 진행 중 전이를 원자적으로 수행한다. 만료 후 새 등록은 새 ID를 가지며 늦게 도착한 옛 요청이 새 입장 기회를 소비하지 못해야 한다. 헤더 이름은 X-WAITING-ID이고 응답 필드 조합은 §12.7을 따른다. ID는 §12.11의 UUID 문자열 규약을 따른다. 기존 DB 쿠폰 반환은 권한 검사보다 우선하므로 ID 누락/만료만으로 기존 결과 반환을 막지 않는다.

### 12.7 waiting API의 응답·헤더·오류 — 합의

기존 X-USER-ID 식별·ApiResponse·null 필드 생략 규칙을 유지한다. 대기 등록/조회는 `POST/GET /api/v1/coupon-campaigns/{campaignId}/waiting`, 발급은 기존 `POST /api/v1/coupon-campaigns/{campaignId}/issues`를 사용한다. 대기 등록·발급의 본문은 없고 발급 권한은 `X-WAITING-ID` 헤더로 전달한다.

| 등록/조회 상황 | HTTP·결과 |
|---|---|
| 새 대기 등록 | 201·WAITING·새 waitingId·현재 순번 |
| 유효 항목에 재등록 | 200·기존 waitingId·현재 상태, 기한 연장 없음 |
| READY 기한은 지났지만 스케줄러 회수 전 재등록 | 기존 항목의 EXPIRED 상태 안내, 새 항목 생성/자리 회수 없음. 회수 후 새 등록 가능. HTTP는 기존 항목 안내의 200을 재사용하는 구체화안 |
| 이미 쿠폰을 보유한 사용자의 등록 | 200·ISSUED·기존 couponId. 새 대기 항목 없음. 등록 입구 RateLimiter는 이 경우도 먼저 적용 |
| 본인 대기 상태 조회 | 200·현재 상태와 해당 정보 |
| 등록 이력 없이 상태 조회 | 404 COUPON_WAITING_NOT_FOUND |
| 품절 후 새 등록 | 409 COUPON_CAMPAIGN_SOLD_OUT |
| 등록 요청률 초과 | 429 COUPON_RATE_LIMITED, DB/Redis 미진입 |
| 기존 쿠폰이 없고 등록의 운영 ID 미설정/불일치 또는 수용 닫힘 | 503 COUPON_ADMISSION_UNAVAILABLE. 입구 요청률 제한과 기존 결과 조회 이후 판정 |
| 기존 쿠폰이 없고 대기 GET의 운영 ID 미설정/불일치 | 503 COUPON_ADMISSION_UNAVAILABLE, Redis 미조회. ID가 일치하면 로컬 닫힘만으로 GET을 막지 않음 |

대기 응답의 data는 campaignId·waitingId·status를 기본으로 다음 정보를 가진다. 이미 보유했고 대기 항목을 만들지 않은 경우처럼 waitingId가 없으면 null 생략 규칙을 적용한다.

| 상태 | 추가 정보 |
|---|---|
| WAITING | position: 현재 대기 순번, 맨 앞은 1 |
| READY | expiresAt: 서버 입장 허용 시점 + 30초의 고정 만료 시각 |
| PROCESSING | 추가 실행 없이 처리 중 상태 안내 |
| ISSUED | couponId: DB에서 확인한 기존 쿠폰 ID |
| EXPIRED / FAILED / SOLD_OUT | 종료 상태 표시 |

상태 조회는 본인·캠페인의 현재 항목을 반환한다. 기존 쿠폰 결과가 확인되면 ISSUED가 우선이고, 품절로 남은 대기가 종료되면 SOLD_OUT을 표시한다. 필드의 Java 타입·시각 문자열 형식과 terminal 보관/덮어쓰기는 §12.11을 따른다.

| 발급 상황 | HTTP·결과 |
|---|---|
| 기존 쿠폰 확인 | 200·기존 다섯 필드·같은 couponId. ID 없어도 반환 |
| 신규 발급 commit 확인 | 201·기존 다섯 필드의 새 쿠폰 |
| 동일 권한의 발급 진행 중 | 202·ApiResponse 성공·PROCESSING 대기 응답 |
| X-WAITING-ID 누락 | 400 COUPON_WAITING_ID_REQUIRED |
| ID 없음 또는 사용자/캠페인 불일치 | 404 COUPON_WAITING_NOT_FOUND |
| 아직 WAITING | 409 COUPON_WAITING_NOT_READY |
| 입장 권한 만료 | 409 COUPON_WAITING_EXPIRED |
| 실패로 이미 사용된 권한 | 409 COUPON_WAITING_ALREADY_USED |
| 기존 쿠폰이 없고 발급의 운영 ID 미설정/불일치 또는 수용 닫힘 | 503 COUPON_ADMISSION_UNAVAILABLE. 기존 결과·헤더 누락 검사 뒤 start 이전 판정 |

발급은 공통 사용자 식별·경로 인자 변환 뒤 기존 쿠폰 확인 → 권한 헤더/항목 검증 → 상태에 따른 응답 또는 원자적 실행 시작 순서로 처리한다. 헤더를 MVC 필수 입력으로 선언해 기존 쿠폰 조회 전에 누락 400을 내지 않는다. 202가 최초 요청의 비동기 전환이나 새 작업 예약을 뜻하지 않도록 한다. 입력 오류·비활성·품절·DB 오류·닫힘/Redis 장애는 기존 원칙을 유지하되 여러 조건이 겹치는 전체 분기 우선순위는 주요 메서드 의사코드에서 정리한다.

이 표는 사용자 승인한 계약이며 실행 검증 결과가 아니다. 표의 각 응답·부작용 없음과 기존 쿠폰 우선, 등록 한도 소진 중 유효 입장 발급 등을 신규 검증 기준에 포함한다.

### 12.8 Redis 키·상태와 네 Lua 작업 — 구체화안

사용자는 키를 `waiting:meta:v1:{campaignId}` 형식으로 단순화하도록 요청했고, Java 스케줄러가 Lua를 호출하는 흐름을 확인했다. 아래 구체화안은 §12.11~12.13과 함께 정상 범위 최종 승인에 포함됐다. 구현/실행 결과와 구분한다. `{campaignId}`는 캠페인 ID가 들어갈 위치 표기이며 실제 예는 `waiting:meta:v1:42`다.

| 키 | 자료구조 | 역할 |
|---|---|---|
| waiting:meta:v1:{campaignId} | Hash | 다음 신규 등록 순번·품절 표시 |
| waiting:entries:v1:{campaignId} | Hash | 사용자 ID 문자열 → 현재 항목 기록 |
| waiting:queue:v1:{campaignId} | Sorted Set | 사용자 ID 문자열을 신규 등록 순번으로 정렬 |
| waiting:ready:v1:{campaignId} | Sorted Set | 사용자 ID 문자열을 입장 권한 만료 시각으로 정렬 |
| waiting:processing:v1:{campaignId} | Set | 진행 중 실행이 점유한 사용자 ID 문자열 |

현재 master String template을 재사용하고 ID를 숫자 변환으로 잃지 않도록 사용자 ID를 문자열 member/field로 전달한다. entries의 직렬화는 §12.11의 JSON 문자열 규약을 따른다. 별도 current-user 인덱스를 추가하지 않고 entries 자체를 사용자별 현재 항목 조회에 사용한다.

항목의 대표 정보는 waitingId·status·sequence·expiresAt이다. 사용자별 최신 항목 하나만 유지하는 안이며 과거 대기 이력 저장은 추가하지 않는다. 스케줄러가 만료를 회수해 EXPIRED로 기록한 뒤 또는 실패가 확정된 뒤 새 등록하면 새 waitingId와 순번으로 교체하고, 옛 ID는 현재 항목과 불일치하면 미발견으로 처리한다. 기존 DB 쿠폰 우선 원칙은 그대로다. waitingId는 서버가 생성하는 UUID 문자열로 구체화한다.

실행 자리 점유는 ready 개수 + processing 개수로 계산해 상한 3 이내로 유지한다. 별도 점유 카운터를 중복 관리하지 않는다. 키 TTL로 진행 중인 점유를 회수하지 않으며 대기 중 항목에도 시간 만료를 두지 않는다.

| Lua 작업 | 판단·변경 개요 |
|---|---|
| 등록 | 현재 WAITING/유효 READY/PROCESSING은 재사용. 기한이 지난 READY가 아직 남아 있으면 EXPIRED 안내만 하고 스케줄러 정리를 기다림. 신규 또는 회수 완료 EXPIRED/확정 FAILED이면 품절 여부 확인 → 새 순번/ID·현재 항목 생성/교체·queue 추가. 등록 Lua에서 만료 회수·입장 배정을 하지 않음 |
| 스케줄러 | 미사용 READY 만료 회수 → 품절이면 추가 배정 중단 → ready+processing으로 빈자리 계산 → queue 앞순번부터 빈자리 수 이내 READY 전환·30초 기한 설정 |
| 실행 시작 | 현재 ID/사용자/캠페인/상태/기한 검사 → READY만 ready 제거·PROCESSING 전환·processing 추가. 이미 PROCESSING이면 처리 중, 기한 경과면 만료 결과. 품절 추가 검사·만료 회수·재등록/입장 배정을 넣지 않음 |
| 종료 기록/반환 | DB 종료 확인을 전제로 현재 waitingId와 PROCESSING 상태만 대조 → 맞으면 결과 기록·자기 processing 제거, 다르면 변경 없음. 별도 종료 호출 이력·재조정·자동 재시도 로직은 두지 않음 |

Lua 안에서 확인한 Redis 서버 시각으로 기한을 판단하고 현재 시각이 만료 시각 이상이면 만료하는 안이다. Redis 시각·수치/키 검증·반환 규약은 §12.11~12.12를 따른다. DB TX를 Redis Lua 안으로 넣지 않는다. DB 결과로 품절이 확인된 경우만 meta에 반영하고, 스케줄러는 새 입장 배정을 중단하며 조회에서 품절 종료를 표현한다. 이미 입장한 사용자의 실제 발급 가능 여부는 기존 DB 조건부 UPDATE가 판단한다. 실행 시작 Lua에서 품절을 중복 판정하거나 품절을 이유로 READY/PROCESSING을 대량 정리하지 않는다. 미사용 READY는 기존 만료 회수 규칙을 따른다.

조회는 기존 DB 쿠폰을 먼저 확인하고 없으면 읽기 전용 Lua로 현재 항목·순번·품절을 같은 시점에 읽는 구체화안이며 반환 규약은 §12.11을 따른다. 이는 네 변경 Lua와 별개인 읽기 작업이며 승격/회수/기한 연장을 수행하지 않는다.

단순화 합의는 만료 회수를 스케줄러 한곳에 두고, 품절의 최종 판단은 DB에 남기며, 종료 Lua는 ID·진행 상태 대조만 하는 것이다. 등록/실행 시작 Lua에서 다른 작업의 정리까지 대신하지 않는다. 비용은 만료 시각부터 다음 스케줄러 회수까지 재등록이 잠시 지연되고, 품절 직전 입장한 요청이 DB에서 품절 응답을 받을 수 있다는 점이다. 새 API 상태 코드를 늘리기보다 기존 EXPIRED 안내 형식을 재사용하는 구체화안으로 둔다.

입력/키 타입 검증·현재 ID/사용자/상태 대조·기한 검사·ready+processing 상한·DB 종료 전 반환 금지는 정상 계약을 지키는 최소 검사로 유지한다. 조회용 Lua도 현재 항목·순번·품절을 읽는 일만 수행하며 정리·승격 분기를 추가하지 않는다. 아직 필요한 상세 작업은 이 단순한 네 작업의 입력/반환값·쓰기 전 검증·의사코드와 최소 테스트 기준을 §12.11~12.13으로 고정했고, 전체 최종 검토·구현 착수는 별도다.

### 12.9 클래스 책임과 주요 메서드 흐름 — 사용자 동의한 구체화안

사용자는 책임 배치·흐름·캠페인 DB 조회·fixed delay와 시작 설정의 운영 캠페인 ID 지정 방향에 동의했고, 스케줄러 메서드명은 advanceWaiting 대신 입장 처리를 나타내도록 변경을 요청했다. 구체화 명칭은 processAdmissions로 한다. 대기 등록 registerWaiting과 접수 후 입장 허용 processAdmissions를 구분한다. 이 구체화안은 정상 범위 최종 승인에 포함되며 코드 착수는 아직이다.

| 타입 | 책임·변경 |
|---|---|
| interfaces/api/coupon/CouponV1Controller·ApiSpec·Dto | 기존 발급/목록에 waiting 등록·조회 추가. 사용자/헤더 입력과 200/201/202·ApiResponse 변환 |
| application/coupon/CouponFacade | registerWaiting·getWaiting·issue·processAdmissions의 DB 읽기/쓰기·대기 서비스 호출 순서 조율. 기존 목록과 DB 종료 판정 재사용 |
| application/coupon/CouponAdmissionService | 로컬 accepting·수용량과 대기/입장/실행 전이 조율·Redis 결과/예외 해석. 기존 acquire/release의 이중 경쟁을 대기 전이로 대체 |
| domain/coupon/CouponAdmissionRepository | register·find·processAdmissions·start·finish의 입력/결과 포트. Redis 기술 타입을 노출하지 않음 |
| infrastructure/coupon/CouponAdmissionRedisRepository | 기존 master String template 재사용, 네 변경 Lua/읽기 Lua 실행·결과 변환 |
| interfaces/scheduler/CouponWaitingScheduler | Java fixed delay 작업으로 CouponFacade.processAdmissions 호출. DB/Lua 업무 처리는 Facade/저장소에 맡김 |
| 대기 등록 요청률 제한의 설정·interceptor·MVC 등록 config | 사용자 존재 조회 전에 대기 등록 POST만 인스턴스 공유 허가 판단. 타입/bean 이름은 §12.11의 최종 검토용 조립안을 따름 |

CouponCampaignRepository·CouponRepository·CouponQueryRepository와 DB 모델·조건부 UPDATE/INSERT·목록은 재사용한다. Controller는 기존처럼 Facade만 호출하고, 별도 전달용 WaitingFacade/WaitingService·전용 발급 worker·새 DB TX 서비스를 추가하지 않는다. Redis 호출 동안 Facade의 상위 DB TX를 열지 않는다.

```text
registerWaiting(userId, campaignId):
  // MVC: 등록 RateLimiter → 기존 사용자 식별/인자 변환 후 진입
  기존 쿠폰 = 짧은 읽기 TX에서 조회, TX 종료
  있으면 ISSUED와 기존 couponId 반환
  admission에서 설정됨/운영 campaignId 일치/로컬 수용 열림 확인
  안 맞으면 기존 503 COUPON_ADMISSION_UNAVAILABLE
  캠페인 = 짧은 읽기 TX에서 존재/상태/잔여 정보로 복사, TX 종료
  없음/비활성/품절이면 기존 업무 오류
  등록 Lua 호출
  신규이면 201 WAITING, 유효 기존 항목이면 200 현재 상태
  회수 전 만료 READY이면 200 EXPIRED 안내 구체화안, 새 항목 생성 없음

getWaiting(userId, campaignId):
  기존 쿠폰 = 짧은 읽기 TX에서 조회, TX 종료
  있으면 ISSUED와 couponId 반환
  운영 campaignId 설정/요청 ID 일치 확인, 아니면 기존 503
  읽기 Lua로 본인 현재 항목/순번/품절 조회
  없으면 404, 있으면 상태별 응답
  조회에서 회수/승격/기한 연장 없음

processAdmissions():
  로컬 수용 닫힘이면 이번 실행 종료
  시작 설정으로 지정한 운영 campaignId 사용
  짧은 읽기 TX에서 캠페인 상태/품절 여부를 값으로 확보, TX 종료
  스케줄러 Lua 호출
    미사용 만료 READY 회수
    DB에서 확인한 품절을 meta에 반영
    품절이면 새 입장 없이 종료
    빈자리만큼 앞순번 READY 전환, 30초 고정 기한 설정
  // Java 작업 종료 후 1초 기다려 다음 실행

issue(userId, campaignId, waitingIdHeader):
  기존 쿠폰 = 짧은 읽기 TX에서 조회, TX 종료
  있으면 헤더 검사 없이 200 기존 결과
  X-WAITING-ID와 설정됨/운영 campaignId 일치/로컬 수용 열림 확인
  시작 Lua 호출
    오류 상태면 합의한 400/404/409
    이미 PROCESSING이면 202, 여기서 종료
    READY → PROCESSING 성공 시에만 아래 DB 발급 실행
  기존 쓰기 TransactionTemplate로 조건부 증가 → 쿠폰 INSERT
  기존 중복/품절 후속 읽기와 DB 종료 판정을 재사용
  DB 호출 종료가 확인되면 finish Lua로 결과 기록·자리 반환
  종료 불명이면 processing 유지·로컬 수용 닫힘
  확정한 업무 결과(201/기존 200) 또는 기존 오류 반환
```

DB 결과 확인 전 자리 반환 금지와 반환 실패가 이미 commit된 쿠폰을 취소하지 않는 기존 원칙을 유지한다. 오류·비활성·품절 등 정상 업무 결과와 DB 종료 불명은 구분한다. 상세한 장애 수동 재개와 epoch는 후속 범위다.

품절 확인을 스케줄러의 짧은 DB 조회에 포함해 별도 품절 감지 루프나 마지막 발급 감지 분기를 추가하지 않는다. 비용은 실행마다 캠페인 조회 한 번과 다음 실행까지의 품절 전파 지연이다. 이미 입장한 사용자는 DB에서 품절될 수 있다. 메타 품절 상태는 §12.12에 따라 true 방향으로만 반영한다.

설정·기동과 기존 수용 제어 정책은 §12.10을 따른다. DTO·Lua 결과 규약과 W6/O11 bean 격리 목록은 §12.11~12.13을 따른다. 보류한 장애 복구 기능을 새로 구현하지 않는다.

### 12.10 설정·기동·수용 제어 — 합의

| 시작 설정 | 기본값·계약 |
|---|---|
| commerce.coupon.admission.capacity | 기존 3 유지 |
| commerce.coupon.waiting.campaign-id | 운영 캠페인 ID 지정. 미지정이면 앱은 기동하고 대기실은 비활성 |
| commerce.coupon.waiting.scheduler-delay | 1초 fixed delay |
| commerce.coupon.waiting.admission-duration | 30초 |

설정은 시작 시 읽고 변경은 재시작으로 적용한다. 캠페인 ID 미지정이면 스케줄러가 DB/Redis 입장 처리를 수행하지 않고 새 대기 등록/발급 시작은 활성화하지 않는다. 기존 보유 목록과 DB에 있는 쿠폰 결과 조회는 유지한다. 캠페인 ID를 지정해도 수용은 닫힘으로 시작하고 명시적으로 open한다. 캠페인 하나의 운영 범위 가정은 유지하고 별도 다중 캠페인 차단 관리 기능은 추가하지 않는다.

| 제어 API | 확장 계약 |
|---|---|
| GET /api/coupon/admission | 기존 열림/닫힘 반환. 캠페인 미설정이면 닫힘 |
| POST /api/coupon/admission/close | 신규 등록·입장 배정·발급 시작 중단. 진행 중 발급의 DB 종료 처리·자리 반환은 계속 |
| POST /api/coupon/admission/open | 캠페인 설정이 있을 때 명시 수용 시작. 미설정이면 409 COUPON_ADMISSION_OPERATION_REJECTED |

close는 대기 항목을 삭제하거나 진행 중 발급을 취소하지 않는다. 기존 쿠폰 결과 반환은 새 진입 차단과 구분한다.

사용자 승인으로 확장 구현에서는 `POST /api/coupon/admission/cleanup` API와 그 전용 요청/응답·Facade/서비스 수동 token 삭제 경로를 제거한다. 기존 cleanup은 기본 세마포어 Set의 지정 token을 SREM하던 API로, 새 정상 흐름의 정리는 스케줄러/종료 Lua가 맡는다. 409만 반환하는 호환용 cleanup이나 새 대기 키를 지우는 대체 API를 두지 않는다. 이는 §8의 기본 구현 당시 수동 정리 계약을 확장에서 대체하는 결정이며 `4711dfe`의 과거 구현·검증 기록은 보존한다. 장애별 정리/복구 기능은 후속 범위다. 문서의 제거 결정과 실제 코드 삭제 완료를 구분한다.

### 12.11 입출력·표현·기동 조립 — 최종 검토용 구체화

이 절 이하의 타입 이름·직렬화·반환 규약은 합의한 정책을 구현 가능한 형태로 마감했고 정상 범위 최종 승인에 포함됐다. 구현 착수 승인은 아니다. 이후 구현 중 실제 계약 공백을 발견하면 추측하지 않고 사용자에게 보고한다.

#### 값·응답 타입

- `domain/coupon/CouponWaitingState`: WAITING, READY, PROCESSING, ISSUED, EXPIRED, FAILED, SOLD_OUT.
- `domain/coupon/CouponWaitingSnapshot`: campaignId Long, waitingId String, status CouponWaitingState, position Long, expiresAt Instant, couponId Long. 상태별 불필요 필드는 null이고 API에서 기존 null 생략 규칙을 따른다. position은 queue의 0 기반 rank+1이며 WAITING에만 있다. 쿠폰 ID는 DB에서 확인한 값만 채운다.
- `application/coupon/CouponWaitingResult`: created boolean과 snapshot. 새 등록만 created=true이고 재등록/기존 결과/조회는 false다.
- `application/coupon/CouponIssueOutcome`: 기존 CouponIssueResult 또는 PROCESSING snapshot 중 정확히 하나. 200/201은 기존 CouponResponse 다섯 필드, 202는 WaitingResponse다. 같은 발급 POST에 두 응답 스키마가 있음을 ApiSpec에도 표현한다. 둘 다 없거나 둘 다 있으면 내부 불변식 오류다.
- `CouponV1Dto.WaitingResponse`: §12.7의 여섯 필드(campaignId, waitingId, status, position, expiresAt, couponId). 숫자 필드는 Long. expiresAt은 기존 목록 시각과 동일한 UTC ISO-8601·소수 6자리·Z 문자열로 표현한다. Redis 밀리초 정밀도 이하 자리는 0으로 채운다. 예: `2026-11-12T00:00:30.123000Z`.
- waitingId는 서버의 UUID 문자열이다. 고객 입력은 opaque 문자열로 비교하며 UUID 파싱을 추가하지 않는다. 기존 결과 확인 뒤 헤더 누락/공백은 COUPON_WAITING_ID_REQUIRED, 알 수 없는 값/옛 ID/사용자·캠페인 불일치는 NOT_FOUND다.
- entries의 Hash field와 queue/ready/processing member는 userId의 십진 문자열이다. Hash value는 JSON 문자열로 waitingId·status·sequence·expiresAtMillis를 보관한다. sequence와 expiresAtMillis는 십진 문자열로 저장해 JSON의 ID/정수 반올림을 피한다. WAITING은 expiresAtMillis 없음, READY에서 기한을 한 번 정하고 PROCESSING에서도 재설정하지 않는다. 종료 시에는 응답에서 expiresAt을 노출하지 않는다.
- 사용자별 최신 항목만 보관하고 terminal 항목은 새 등록/후속 수동 초기화까지 남긴다. 과거 항목 이력·terminal TTL·백그라운드 청소기를 추가하지 않는다. 기존 DB 쿠폰이 우선이므로 Redis ISSUED에 couponId 캐시를 추가하지 않는다. DB에 쿠폰이 없는데 Redis에 ISSUED만 남은 모순은 성공을 만들지 않고 내부 오류로 드러낸다.

#### 포트와 반환 규약

CouponAdmissionRepository에 아래 변경 계약을 둔다. 메서드 결과 record/enum은 해당 포트 안에 둘 수 있으며 HTTP/Redis/Spring 타입을 노출하지 않는다. 인수 userId/campaignId는 검증된 Long이고 waitingId는 String, 기한 인수는 양의 밀리초다.

| 메서드 | 입력 | 결과 |
|---|---|---|
| register | campaignId, userId, 새 waitingId 후보 | CREATED/EXISTING과 snapshot 또는 SOLD_OUT/INVARIANT_ERROR. 회수 전 만료 READY는 EXPIRED snapshot을 EXISTING으로 반환 |
| find | campaignId, userId | Optional snapshot. DB 결과 합성은 Facade 책임 |
| processAdmissions | campaignId, capacity, admissionDurationMillis, DB에서 읽은 soldOut, DB에서 읽은 canAdmit | 회수 수·신규 입장 수. canAdmit=false면 회수만 하고 새 입장 없음 |
| start | campaignId, userId, waitingId | STARTED/PROCESSING/ISSUED/NOT_FOUND/NOT_READY/EXPIRED/ALREADY_USED/INVARIANT_ERROR. PROCESSING/ISSUED는 현재 snapshot 포함 |
| finish | campaignId, userId, waitingId, ISSUED 또는 FAILED | APPLIED/UNCHANGED/INVARIANT_ERROR. DB 종료 확인은 호출자 전제 |

읽기/변경은 master String template으로 수행한다. Lua 반환은 String 원소의 고정 배열로 규격화한다. 기본은 `[결과명, 항목 JSON 또는 빈 문자열, position 또는 빈 문자열]`, processAdmissions는 `[OK, 회수 수 문자열, 입장 수 문자열]`, finish는 `[APPLIED 또는 UNCHANGED 또는 INVARIANT_ERROR]`다. Java adapter는 길이/결과명/JSON/숫자를 검증해 타입으로 변환한다. null·형식 오류·알 수 없는 상태를 수용 성공으로 바꾸지 않는다. 등록의 품절 결과는 SOLD_OUT, 그 밖의 설명 불가 상태는 INVARIANT_ERROR로 구분한다.

#### 설정과 bean

| 타입 | 설정·조립 |
|---|---|
| 기존 CouponAdmissionProperties | 기존 capacity=3·양수 검증 유지 |
| 신규 config/CouponWaitingProperties | prefix commerce.coupon.waiting. nullable Long campaignId, schedulerDelay=1s, admissionDuration=30s. ID 생략 허용, 명시 ID는 양수. 두 Duration은 밀리초로 양수 표현 가능해야 하며 형식/범위 오류는 기동 실패 |
| 신규 config/CouponRateLimitProperties | prefix commerce.coupon.rate-limit. limitForPeriod=10, refreshPeriod=1s. 양수/형식 검증 및 Resilience4j 설정 생성 범위 검증 |
| 신규 interfaces/api/coupon/CouponWaitingRateLimitInterceptor | @Component 이름 couponWaitingRateLimitInterceptor. singleton RateLimiter를 한 번 생성, 대기 등록 handler만 대상·timeout 0. 사용자/캠페인별 생성 없음 |
| 신규 config/CouponWaitingConfig | @Configuration 이름 couponWaitingConfig. MVC interceptor 등록만 담당. @EnableScheduling·@Bean 메서드를 두지 않음. 기존 WebMvcConfig의 resolver 등록 유지 |
| 신규 interfaces/scheduler/CouponWaitingScheduler | @Component 이름 couponWaitingScheduler. SmartLifecycle로 내부 단일 ScheduledExecutorService의 시작/정지를 관리. 실행기는 Spring bean으로 등록하지 않고 W7 relay 실행기를 사용하지 않음. 캠페인 미설정/닫힘이면 업무 호출 없음 |

기존 ConfigurationPropertiesScan을 재사용한다. 설정이 없는 경우에도 위 동작 bean 정의는 존재시키고 실행 경로에서 대기실 비활성을 처리한다. 캠페인 설정 유무로 bean 이름 목록이 달라지지 않게 한다. campaignId 없는 open은 기존 409 OPERATION_REJECTED다. 해당 ErrorType의 cleanup 전용 메시지는 “요청한 수용 제어 작업을 수행할 수 없습니다.”로 일반화하고 HTTP/코드는 유지한다. 신규 수용은 설정/열림 조건이 없으면 기존 503 ADMISSION_UNAVAILABLE이며, 기존 쿠폰 조회/목록은 유지한다. 신규 대기 등록/신규 발급 시작은 설정된 운영 campaignId와 요청 ID도 일치해야 하며 불일치는 기존 503 ADMISSION_UNAVAILABLE이다. 대기 GET도 기존 쿠폰 확인 뒤 같은 ID 일치 조건을 적용하고 불일치면 Redis 조회 없이 503이다. GET은 로컬 닫힘만으로 막지는 않으며 정상 Redis 상태 조회는 계속 가능하다. 기존 쿠폰 조회는 이 검사보다 우선한다. 이는 처리 대상 검증이며 다중 ACTIVE DB 제약·자동 운영 전환 기능은 추가하지 않는다. 일반 조회에 별도 RateLimiter를 추가하지 않으며 조회·잘못된 권한 호출의 부하 격리를 보장한다고 주장하지 않는다.

W6 준비·O11의 CouponBeanIsolation에는 추가 동작 bean `couponWaitingRateLimitInterceptor`, `couponWaitingConfig`, `couponWaitingScheduler` 세 이름을 정확히 추가한다. 동작 없는 Waiting/RateLimit Properties는 유지한다. 기존 쿠폰 bean 이름·관리 타입·DDL·행 보존 검사는 유지한다. 기본 준비 코드 CouponLocalFixtureConfig의 격리 방식은 바꾸지 않는다. 현재 소스에는 이 추가 bean이 없으므로 목록/새 bean/검증은 구현 때 함께 변경한다.

#### 쿠폰 전용 실행기의 최소 lifecycle — F4 보완

- CouponWaitingScheduler만 내부 실행기를 소유한다. start는 중복 호출에 안전하게 한 번만 주기 작업을 등록한다. campaignId가 없으면 실행기를 만들거나 업무 회차를 예약하지 않는다. bean 정의 자체는 유지한다.
- campaignId가 있으면 단일 스레드 실행기 하나로 scheduleWithFixedDelay를 사용한다. 소유 스레드 이름은 coupon-waiting- 접두사로 구분한다. 최초 실행도 schedulerDelay 뒤에 시작하고 각 실행 종료 후 같은 지연을 둔다. 제품 기본 1초, @Async·병렬 회차·공통 @EnableScheduling·별도 TaskScheduler bean은 없다.
- 한 회차는 CouponFacade.processAdmissions만 호출한다. Redis 오류는 아래 중단 표에 따라 서비스가 수용을 닫고, 주기 경계에서 RuntimeException을 로그로 남긴다. Error도 로그로 드러낸 뒤 다시 던져 이후 회차가 중단된 사실을 숨기지 않으며 자동 재시작하지 않는다. 다음 예약이 남아 있어도 닫힘 상태에서는 DB/Redis 작업이 없다. 주기 실행을 사용자 발급의 재시도로 취급하지 않는다.
- stop은 주기 예약 취소(cancel(false)) → 소유 실행기 shutdown → 진행 회차의 종료를 유한하게 기다리는 순서다. 이미 진행 중인 회차의 취소 성공을 가정하지 않는다. 대기 한도는 기존 spring.lifecycle.timeout-per-shutdown-phase(기본 30초)를 사용하고, interrupt면 복구하며 미완료는 로그로 구분한다. 미완료를 정상 종료로 기록하지 않는다. 수용 플래그의 open/close 계약과 주기 작업 start/stop은 별개다.
- SmartLifecycle phase는 기본 DEFAULT_PHASE를 사용하고 W7의 -100을 복사하지 않는다. isRunning/stop의 반복 호출과 컨텍스트 종료 후 새 회차 없음·자기 스레드 종료를 WQ11에서 확인한다. W7의 pool·gate·phase·enabled 및 기존 lifecycle 구현은 수정하지 않는다. 이번 범위를 넘어서는 비정상 재시작/강제 종료 복구는 후속이다.

### 12.12 단순 Lua·Facade 상세 전이 의사코드

Redis 서버 TIME을 각 Lua 안에서 한 번 읽어 밀리초 정수로 비교한다. now >= expiresAt이면 만료다. 신규 순번은 meta의 nextSequence를 증가시켜 부여하고 Sorted Set의 정확 정수 표현 범위 안에서 사용한다. 순번 소진은 새 등록을 수행하기 전에 내부 오류로 드러내며 기존 항목을 덮어쓰지 않는다. DB 트랜잭션과 Lua를 한 원자 트랜잭션으로 설명하지 않는다.

각 스크립트는 입력·키 타입(none 또는 기대 타입)·현재 기록의 필수 값·변경 대상 상태를 쓰기 전에 검증한다. 잘못된 키/기록을 복구하거나 삭제하는 분기를 추가하지 않는다. Lua를 DB rollback처럼 취급하지 않으며 중간 오류는 성공으로 해석하지 않는다. 정상 흐름의 대상은 한 캠페인·한 앱 인스턴스다.

```text
register Lua:
  현재 사용자 entry 읽기
  WAITING/PROCESSING이면 기존 snapshot 반환
  READY이면
    기한 전: 기존 READY 반환
    기한 경과: EXPIRED snapshot만 반환(저장/회수/새 등록 없음)
  ISSUED이면
    EXISTING과 ISSUED snapshot 반환, 새 대기 생성 없음
    // Facade가 선행 DB 조회와의 commit 경합을 한 번 재조회로 판별
  신규 또는 저장된 EXPIRED/FAILED이면
    meta.soldOut이 true면 SOLD_OUT 결과
    새 UUID 후보·순번 범위 검증
    nextSequence 증가 → 새 WAITING entry 기록 → queue에 userId 추가
    CREATED 반환

스케줄러 Lua:
  현재 ready+processing이 C 이내인지 쓰기 전 검증
  ready에서 expiresAt <= now인 대상 조회(상한 C 이내)
  대상 entry가 READY이고 기록/score가 맞는지 검증
  회수 후 예상 빈자리 계산
  DB soldOut 또는 기존 meta.soldOut이면 새 배정 수는 0
  canAdmit=false이면 새 배정 수는 0
  배정할 queue 앞 항목을 읽고 모두 WAITING인지 쓰기 전 검증
  검증이 끝난 만료 대상 ready 제거·EXPIRED 기록
  DB soldOut=true이면 meta.soldOut을 true로만 설정
  검증이 끝난 대상만 queue 제거·READY 기록(now+30초)·ready 추가
  회수/배정 수 반환

start Lua:
  현재 사용자 entry와 waitingId 대조, 없거나 다르면 NOT_FOUND
  WAITING → NOT_READY
  EXPIRED → EXPIRED
  FAILED → ALREADY_USED
  PROCESSING → 현재 snapshot과 PROCESSING, 변경 없음
  ISSUED → 현재 snapshot과 ISSUED, 변경 없음(Facade에서 DB 한 번 재확인)
  READY이면 기한 검사
    기한 경과 → EXPIRED 결과만 반환, 회수는 하지 않음
    유효 → ready 제거·PROCESSING 기록·processing 추가, STARTED
  그 외 설명 불가 상태 → INVARIANT_ERROR
  // 품절/타인 회수/재등록/배정 분기 없음

finish Lua:
  현재 waitingId·PROCESSING 대조
  안 맞으면 UNCHANGED, 다른 항목/점유 변경 없음
  맞는데 실제 processing 점유가 없으면 쓰기 전 INVARIANT_ERROR
  맞고 점유도 있으면 ISSUED 또는 FAILED 기록·processing 제거
  APPLIED 반환

find 읽기 Lua:
  entry 없으면 NOT_FOUND
  PROCESSING이면 그 상태로 반환(품절로 조기 종료하지 않음)
  저장 상태가 WAITING/READY이고 meta.soldOut이면 SOLD_OUT으로 표현
  그 외 READY에서 기한 경과면 EXPIRED snapshot으로만 표현
  실제 응답이 WAITING이면 queue rank+1, READY이면 expiresAt 추가
  상태/순번을 반환하고 회수/기한 연장/승격은 하지 않음
```

Facade와 서비스의 결과 해석/우선순위는 다음으로 마감한다.

- 등록: MVC limiter → 사용자/인자 검증 → 기존 쿠폰 조회 → 운영 캠페인 설정/ID 일치/로컬 열림 → 캠페인 없음/비활성/품절 → register. Lua에서 ISSUED 동시 경합을 관찰하면 DB를 한 번 다시 읽고 기존 결과로 응답한다. 이는 INSERT 재시도가 아니다. 회수 전 만료 snapshot은 200 EXPIRED, 회수 후 새 등록은 201 WAITING이다.
- 조회: 공통 식별 → DB 기존 쿠폰 → 운영 캠페인 설정/요청 ID 일치 확인 → find. 기존 쿠폰은 Redis 없이 ISSUED 합성, 설정 없거나 운영 ID가 다르면 기존 503이며 Redis 대기 키를 조회하지 않는다. 운영 ID가 일치하고 entry 없음이면 404다. Redis ISSUED와 DB 조회 사이의 commit 경합도 DB 한 번 재조회로 정리한다. GET은 Redis 상태를 변경하지 않는다.
- 발급: 공통 식별 → DB 기존 결과 → 헤더 누락/공백 → 운영 캠페인 설정/ID 일치/로컬 열림 → start. PROCESSING 결과는 DB에서 그사이 commit됐는지 한 번 재확인해 결과가 있으면 200, 아직 없으면 202다. ISSUED 결과도 DB를 한 번 재확인해 기존 쿠폰이면 200, 여전히 없으면 내부 불변식 오류이며 새 발급은 시작하지 않는다. STARTED일 때만 기존 쓰기/후속 읽기 TX를 실행한다. NOT_FOUND/NOT_READY/EXPIRED/ALREADY_USED는 §12.7로 변환한다.
- start 성공 후 결과는 기존 §6.1의 TX 시작 여부·commit/rollback/종료 불명 판정을 재사용한다. DB 작업이 종료됐고 새/기존 쿠폰 결과가 있으면 finish(ISSUED), 응답 미확보여도 쓰기 commit이 확인됐으면 기존 HTTP 결과를 유지하며 finish(ISSUED), 종료된 실패이면 finish(FAILED), 종료 불명은 finish하지 않고 로컬 수용을 닫는다. finish 실패가 이미 확정된 DB 결과를 되돌리지 않는다. 자동 발급/권한 복원/자동 재시도는 없다.
- 스케줄러: campaignId 미설정/로컬 닫힘이면 DB·Redis 호출 없음. 설정됐으면 짧은 읽기 TX에서 캠페인 상태를 값으로 복사한 뒤 TX 종료. soldOut은 DB의 issuedCount >= totalQuota, canAdmit은 캠페인 존재·ACTIVE·미품절이다. 없거나 INACTIVE면 canAdmit=false로 회수만 가능하고 신규 배정은 하지 않는다. 스케줄러는 DB를 변경하지 않는다.
- 이 절의 DB 재확인은 정상 동시 commit 때문에 기존 결과를 놓치지 않기 위한 유한 조회다. 새 대기 권한 발급·DB INSERT·자동 작업 재시도를 추가하지 않는다. 별도 실행 추적 Map·종료 호출 이력·epoch·분산 락을 만들지 않는다.

close는 로컬 진입 허용 판단을 닫는 동작이다. 이미 판단을 통과한 요청/주기 작업의 취소나 완료 대기까지 제공하지 않으며 기존 수용 close의 실행 중 처리 경계를 유지한다. close 뒤 새 진입은 거절하고 이미 시작한 DB 작업의 종료/반환은 계속한다. 새로운 분산 중단 프로토콜을 추가하지 않는다.

캠페인 ID는 운영자가 지정한 단일 운영 대상이라는 범위 가정이다. 과거 캠페인/쿠폰 조회는 유지한다. 여러 캠페인을 동시에 신규 발급 운영했을 때의 승격·공정성·전역 순서는 이번 보장 범위가 아니다.

#### Redis 작업별 결과와 수용 중단 — F2 보완

정상 업무 결과/무변경과 실제 Redis 처리 실패를 구분한다. 각 작업에서 예외·timeout·형식 오류·알 수 없는 응답·Lua INVARIANT_ERROR를 발견하면 로컬 수용을 닫는다. 닫힘은 신규 진입 중단이고 키 초기화·자동 복구·epoch 전환이 아니다.

| 관찰 | 로컬 수용·응답/효과 |
|---|---|
| register의 기존 항목·EXPIRED 안내·SOLD_OUT | 정상 결과. 수용 유지, 합의한 200/409로 변환 |
| start의 NOT_FOUND/NOT_READY/EXPIRED/ALREADY_USED/PROCESSING/ISSUED | 정상 분기. 수용 유지, §12.7 및 기존 결과 재조회 원칙 적용 |
| register/start의 Redis 실패·잘못된 반환·INVARIANT_ERROR | 수용 닫음·503 COUPON_ADMISSION_UNAVAILABLE. DB 발급을 시작하지 않음 |
| find의 NOT_FOUND 또는 정상 상태 | 조회 결과/404. 수용 유지 |
| find의 Redis 실패·잘못된 반환·INVARIANT_ERROR | 조회도 관찰한 Redis 오류이므로 닫음·503. 기존 DB 결과가 먼저 확보됐으면 이 Redis 호출 자체를 하지 않음 |
| processAdmissions의 정상 회수/배정 수 또는 0건 | 정상 결과. 수용 유지 |
| processAdmissions의 Redis 실패·잘못된 반환·INVARIANT_ERROR | 수용 닫음·로그. HTTP 응답은 없으며 다음 회차는 닫힘으로 업무 호출 없음 |
| finish의 APPLIED | 정상 종료 기록/자리 반환 |
| finish의 UNCHANGED | 현재 ID/진행 상태가 맞지 않아 변경하지 않은 정상 결과. waitingId를 포함한 WARN 로그만 남기고 단순 반복/옛 호출 결과만으로 수용을 닫지 않음 |
| finish의 Redis 실패·잘못된 반환·INVARIANT_ERROR | 수용 닫음·로그. 이미 정해진 DB 업무 결과/HTTP를 유지하고 자동 재시도하지 않음 |

finish에서 현재 ID·PROCESSING이 맞는데 실제 processing 점유가 없으면 UNCHANGED로 숨기지 않고 쓰기 전 INVARIANT_ERROR로 구분한다. 이는 현재 진행 점유의 최소 일관성 검사이며 별도 호출 이력/복구 추적을 추가하지 않는다. 기본 SREM의 “정상 첫 반환 0”과 새 포트의 정상 UNCHANGED는 같은 의미가 아니다.

Facade의 ISSUED 관찰 후 DB 한 번 재조회에도 쿠폰이 없는 모순은 기존 500 내부 오류로 드러내고 수용을 닫는다. commit은 확인했지만 응답 결과를 확보하지 못한 기존 분기는 API 결과를 임의 성공으로 만들지 않는다. 쓰기 commit이 확인됐으면 finish(ISSUED)로 점유를 종료하고, 원래 500/후속 재요청 확인 계약은 유지한다. 종료가 확인된 실패만 finish(FAILED)다. DB 종료 불명은 finish하지 않는다.

수용이 열려 있고 운영 ID가 일치할 때 품절/비활성 뒤 기존 WAITING 사용자가 POST 재등록하면 선행 DB 검사로 409이고, GET은 200 SOLD_OUT(품절 표시 확인 시)일 수 있다. 닫힘 중 신규 발급 진입은 503이며 진행 중 중복의 202보다 로컬 진입 조건이 먼저다. 기존 DB 쿠폰 반환은 계속 모든 입장 조건보다 우선한다. 이는 합의한 처리 순서를 명시한 것이며 상태/예외별 복구 기능을 추가하지 않는다.

### 12.13 검증 체크리스트와 기본 검사 전환 — 전부 실행 전

| ID | 필수 관찰·완료 기준 |
|---|---|
| WQ1 | 설정 생략/명시 값·양수/형식·밀리초 표현 검증, ID 없을 때 앱/기존 기능 유지·대기실 비활성, 기본 닫힘·open/close·미설정 open 409·운영 ID 불일치 신규 진입 503 |
| WQ2 | 실제 Redis Lua 등록. 동일 사용자 동시 등록은 하나의 ID/queue 항목, 신규 순번은 서버 확정 순서, 다른 사용자는 각각 한 항목. 유효 재등록은 ID/기한 불변 |
| WQ3 | C=3에서 4명 이상 등록·승격. READY+PROCESSING<=3, 네 번째 대기·DB 미진입. 발급 종료 후 다음 스케줄러에서 앞순번 승격 |
| WQ4 | READY 30초 만료/조회 비연장/PROCESSING 미회수. 만료-시작 동시 경쟁은 시작 또는 만료 중 하나, 이중 반환/초과 실행 없음. 회수 전 등록 200 EXPIRED·회수 후 새 ID/맨 뒤 |
| WQ5 | start의 ID 없음/잘못된 사용자·캠페인/옛 ID/대기/만료/사용됨 응답과 부작용 없음. 같은 권한 동시 발급은 STARTED 하나·나머지 PROCESSING 또는 완료 후 ISSUED, 기존 쿠폰이 확인되면 200. B의 선행 DB 조회 뒤 A가 finish까지 끝난 경합 포함 |
| WQ6 | finish ID/상태 대조·자기 자리만 반환·반복/옛 종료 UNCHANGED는 무변경·수용 유지, 실제 점유 불일치는 INVARIANT·닫힘. 실패 뒤 권한 복원 없음. 새 등록 뒤 옛 종료가 새 항목을 바꾸지 않음 |
| WQ7 | 실제 MySQL의 총량/사용자 중복 제약·조건부 UPDATE/INSERT 단일 TX·INSERT 실패 rollback·현재 이름/과거 혜택·목록/기존 결과 보존. 기존 Q1~Q5/Q13~Q15의 유효한 검증 유지 |
| WQ8 | 실제 MVC에서 등록 제한이 사용자 DB/Redis보다 선행. 기본 1초/10개·대기 0 및 변경/소진/재허용/공유/동시성. 등록 한도 소진 중에도 유효 입장 발급·목록·제어는 이 limiter에 거절되지 않음 |
| WQ9 | 등록 201/200·조회 200/404·발급 200/201/202와 모든 오류 표·null 생략·시각 형식. 기존 쿠폰은 헤더 누락/만료·닫힘·품절 뒤에도 우선 반환. 정상 commit-재요청 경합에서 추가 발급 없음. 운영 ID 불일치 등록/실행/GET 503·Redis 대기 키 미조회·기존 쿠폰 결과 우선 |
| WQ10 | DB 품절 snapshot 뒤 새 입장 중단·대기 조회 SOLD_OUT·기존 결과 ISSUED 우선·진행 점유 미회수. INACTIVE/없는 캠페인은 신규 배정 없음. DB/Lua 동시성 때문에 이미 READY인 요청은 DB에서 최종 품절 판단 |
| WQ11 | 조회 요청 없이 Java fixed delay 작업의 회수/승격 진행·캠페인 미설정/닫힘 때 저장소 미호출·close 후 진행 중 finish 유지·스케줄러의 DB 쓰기 0. 작업/컨텍스트 종료 시 소유 자원 정리 |
| WQ12 | Checkstyle·ArchUnit·기존 목록/준비/커머스 검사와 새 bean의 W6 준비/O11 조립. 관리 타입·DDL/행 보존 기대값 불변. W7 relay scheduler lifecycle 검사로 실행기/종료 구성 보존도 확인. 실행 수·skip/명시 실행·미실행은 별도 기록 |

일반 단위/Facade/HTTP/Redis 통합 검증에서는 테스트 조립에서 CouponWaitingScheduler를 대체(예: 해당 테스트의 @MockitoBean)해 자동 회차가 업무를 호출하지 않도록 한다. 수용을 열기 전에 자동 실행이 없음을 확인하고 필요한 회차는 CouponFacade.processAdmissions를 직접 호출한다. 긴 delay만 지정하고 첫 자동 회차까지 통제됐다고 간주하지 않는다. 제품용 자동 실행 스위치·테스트 hook을 추가하지 않는다.

실제 자동 fixed delay·시작/정지·내부 스레드 종료는 WQ11 전용 컨텍스트에서 real CouponWaitingScheduler와 짧은 테스트 delay로 검증한다. W6/O11과 relay lifecycle 검사는 mock으로 조립 문제를 숨기지 않고 실제 정의/수명을 대조한다. 각 테스트 컨텍스트는 고정한 양의 campaign-id를 시작 설정에 넣고, 그 ID로 캠페인을 준비하는 fixture를 사용한다. fixture는 자기 ID/키만 소유·정리하며 동적으로 생성된 임의 ID와 설정이 우연히 맞는다고 가정하지 않는다. 이 고정 ID 요구는 시작 속성이 먼저 바인딩되는 Spring 컨텍스트 테스트 기준이다. CouponFacadeIntegrationTest처럼 Facade/Service/Properties를 직접 조립하는 검사는 fixture가 만든 campaignId를 Properties에 명시 전달하면 되며 고정 ID SQL 준비를 강제하지 않는다.

Redis 시간/만료 검증은 짧은 테스트 전용 admission duration을 사용하되 제품 30초 기본 바인딩을 별도 검사한다. RateLimiter 소진/동시성은 §11에서 읽기 확인한 라이브러리 시간 기준과 긴 테스트 주기를 활용하고 생성 전~관찰 종료가 그 주기 이내인지 확인한다. 재허용은 별도 짧은 주기·유한 대기로 검증한다. 제품에 테스트 시계/hook·전역 추적을 추가하지 않는다. 테스트 컨텍스트마다 상태를 격리하고 자기 키/실행만 정리한다. 공유 Redis FLUSHALL·공유 컨테이너 조작은 이번 범위가 아니다.

| 기본 계약/검사의 변화 | 확장 시 처리·대체 근거 |
|---|---|
| 기존 Q7의 신규 요청 FULL 429 | 사용자 승인으로 대기/입장 자리 배정으로 대체. WQ2~WQ5의 순번·상한·DB 미진입·자리 재사용 검증으로 교체. 단순히 429 기대를 삭제해 상한 검증을 잃지 않음 |
| cleanup API/DTO/전용 서비스 테스트 | 제거 승인에 따라 해당 경로 교체/제거 가능. WQ4/WQ6/WQ11이 정상 만료/종료 반환을 검증. 기본 commit/과거 실행 기록은 보존 |
| 기존 200/201 직접 발급 테스트 조립 | 운영 ID의 정상 신규 발급은 waiting/READY fixture를 갖추고 기존 DB 효과/200·201 응답 기대를 유지. 기존 쿠폰 재요청은 ID 없이도 유지. 아래처럼 승인된 진입 계약 변경 때문에 효력을 잃은 경로 기대만 근거와 대체 검증을 기록해 변경 |
| businessRejections의 여러 ID/없는 ID 발급 | 운영 ID 불일치는 503으로 검증한다. 운영 ID 자체가 DB에 없을 때 등록은 404 CAMPAIGN_NOT_FOUND, 운영 ID가 비활성/품절이면 등록 409를 검증한다. 발급의 비활성/품절 409는 READY 배정 뒤 DB 상태를 바꿔 실제 쓰기 진입으로 검사. finish(FAILED)·쿠폰/issuedCount 추가 효과 없음·같은 권한 재요청 ALREADY_USED를 확인한다. 캠페인 없음은 409가 아닌 404임을 유지 |
| ownCouponsOnly의 A/B/C 발급 준비 | 운영 캠페인 쿠폰은 API로 발급하고 다른 과거 캠페인 쿠폰은 DB fixture로 준비. 사용자 소유권·현재 이름/과거 혜택·createdAt/id 정렬·페이지·총수 기대는 그대로 유지한다. 여러 캠페인의 신규 발급을 한 컨텍스트에서 운영하는 대체 기능은 넣지 않음 |
| 총량·중복 DB 경쟁과 실제 C=3 상한의 분리 | 총량 경합은 현재 lastQuotaRace처럼 테스트용 C=8과 실제 Redis READY/STARTED barrier를 사용해 서로 다른 8명을 실제 MySQL 경쟁에 참여시킬 수 있다. 5 성공/3 품절·쿠폰/issuedCount 기대 유지. 같은 사용자의 UNIQUE 경쟁은 기존 CouponIssueTransactionIntegrationTest의 직접 DB 경쟁을 유지한다. 실제 Lua가 동일 권한 실행을 하나로 제한하는 검증은 WQ5로 별도 수행한다. Facade의 UNIQUE 후속 조회 분기 검증이 필요하면 해당 검사에만 STARTED 테스트 포트를 사용하고 DB 효과는 실제로 관찰한다. 대역 검사를 실제 Redis C=3 상한 근거로 사용하지 않고 WQ3을 유지 |
| 구안 RL1~RL6 | 구현되지 않은 발급 POST 제한 기준은 적용하지 않음. 등록 입구의 WQ1/WQ8/WQ9로 대체. 발급 POST 한도를 높여야 한다는 구안 처방은 새 구조에 그대로 이월하지 않음 |
| 기존 HTTP/Redis/TX 대역 지원 코드 | 바뀐 포트/ID/진입 지점을 맞춰 유효한 응답 유실·종료 전 반환 금지·UNKNOWN 기본 검증을 유지. 단순 시그니처 변경을 이유로 유효 검사를 삭제/완화하지 않음 |
| 실제 Redis pause/재시작/늦은 명령·epoch | 새 장애 시나리오/복구 실험은 명시 보류. 과거 기본 실행은 보존하지만 확장 검증으로 사용하지 않음. 기존 opt-in 지원 소스의 컴파일/조립 수정과 실제 장애 재실행 승인은 구분 |

구현 단위별 검사 범위를 먼저 제안하고 승인받는다. 전체 회귀·실제 장애/부하 실험은 자동 수행하지 않는다. 실패/skip/연결 실패를 통과로 세지 않는다. 문서의 체크리스트는 앞으로의 검증 기준이며 이번에는 어떤 제품 검사도 실행하지 않았다.

로컬 확인 가이드는 기본 §9를 따른다. 준비 코드가 출력한 실제 캠페인 ID를 확인한 뒤, 같은 DB를 보존하는 ddl-auto=update와 fixture.enabled=false로 재시작하면서 waiting.campaign-id를 그 ID로 지정하고 명시 open한다. 기본 local/test의 create로 재시작하면 DB가 재생성되므로 ID/쿠폰 보존을 가정하지 않는다. Redis 초기화/재시작 복구를 이 가이드로 검증했다고 주장하지 않으며 해당 상세는 계속 후속이다. 이번에는 이 가이드의 앱 실행을 수행하지 않았다.

### 12.14 최종 승인·구현 경계와 명시적 후속 범위

2026-11-12 세 차례 Claude 읽기 전용 정적 검토를 거쳤다. 마지막 사용자 전달 결과는 필수 수정/새 사용자 판단/정상 구현 전 blocker 없음, N1/N2/S1~S5 해소·F1~F5 유지였다. S6(API 표의 503·GET 닫힘 예외 표시), S7(Spring 컨텍스트와 직접 조립 테스트의 캠페인 ID 준비 구분)은 계약 변경 없는 표현 보완이다. 사용자가 해당 보완과 함께 현재 정상 범위 설계를 최종 승인하고 기준 문서 staging과 커밋 메시지 준비를 요청했다.

현재 승인 범위는 §12의 정상 대기/입장/발급·기본 중단/종료 판정과 WQ1~WQ12 검증 기준이다. 코드/테스트 작성·실제 실행 검증·구현 단위 착수 승인은 아직이다. 실제 제출/커밋과 최종 제출 판단은 사용자 담당이다. 기존 기본 구현의 실행 기록을 확장 검증 결과로 사용하지 않는다.

별도 사용자 결정/작업 단위로 남긴 것은 캐시·Bulkhead의 채택과 epoch·다중 인스턴스/다중 운영 캠페인·자동 복구·순번 보존·장애별 수동 재개·늦은 명령/상태 유실 검증이다. 이를 현재 정상 구현 범위에 자동 추가하지 않는다. 실제 구현에서 계약 공백/모순이 발견되면 근거를 보고하고 필요한 판단만 사용자에게 확인한다.

## 13. 기발급 결과·품절 캐시 — 최종 승인·A/B 구현/관련 검사 완료

2026-11-12 전체 정적 검토와 C1/S2~S4 보완본 재검토에서 남은 필수 수정/새 정책 판단이 없음을 확인한 뒤, 사용자가 §13/CQ17의 캐시 설계를 최종 승인했다. 이 절은 정상 구현 기준점 `3bc158a` 이후의 추가 설계다. 이후 Redis A와 로컬 B를 구현/검토했고 B의 관련361건·별도O11 1건 통과를 확인했다. 전체 회귀·실제 장애/복구·비교 실험은 아직 미실행이며 상세 근거/대역 구분은 쿠폰 실행 기록을 따른다. §12의 기존 전체 검사 근거는 캐시가 적용되지 않은 기준점이다.

### 13.1 대상과 기본 원칙 — 합의

| 대상 | 합의한 방향 |
|---|---|
| 기발급 결과 | 사용자·캠페인별 이미 발급된 쿠폰 결과를 캐시한다. DB commit이 확인된 결과만 저장한다. 미발급/없음 결과는 캐시하지 않는다 |
| 캠페인 품절 | DB에서 품절을 확인한 경우에만 `true`를 저장한다. 키 없음은 '모름'이며 재고 있음/발급 가능 보장이 아니다. `false`는 저장하지 않는다. TTL 경과 뒤 필요 시 DB에서 재확인한다 |
| 캠페인 정보 전체 | 이번 캐시 대상에서 제외한다. 실제 발급의 조건부 UPDATE·발급 데이터 조회는 계속 필요하며 이를 캐시로 대체하지 않는다 |
| 사용자 존재 여부 | 이번 대상에서 제외한다. 기존 X-USER-ID resolver의 사용자 존재 DB 확인은 유지한다 |

기존 쿠폰 결과 확인은 품절 캐시보다 우선한다. 품절됐더라도 이미 발급받은 사용자는 기존 쿠폰을 반환받아야 한다. 캐시 hit만으로 신규 발급을 허용하지 않으며 DB 총량/사용자 UNIQUE·TX 종료 판정·실행 자리 전이는 유지한다. 기존 Redis 대기실 meta의 품절 표시는 새 캐시의 저장 위치/수명 합의를 대신하지 않는다.

사용자는 미발급 false 선저장 대안을 검토한 뒤 positive 결과만 캐시하는 방향을 재확인했다. 기발급 캐시 miss는 미발급 증거가 아니므로 DB에서 기존 결과를 확인한다. 이후 품절 캐시를 사용하더라도 이 DB 확인을 생략하지 않는다. 캐시 만료/저장 실패와 미발급을 구분하며, 신규 발급 뒤 stale false 또는 늦은 false 채움이 기존 결과를 숨기는 경합을 이번에 추가하지 않는다. 그만큼 미발급 요청의 선행 쿠폰 DB 조회는 남는 비용을 수용한다.

**이름 최신성의 합의:** 기발급 결과에 포함된 campaignName은 캐시로 인한 짧은 반영 지연을 허용한다. 이는 기존 응답의 '조회 시 현재 이름'에서 캐시 도입 시 바뀔 부분이며, 쿠폰 혜택은 계속 발급 당시 값이다. 구체 TTL·적용 API·목록 처리 범위와 늦은 채움의 한계는 §13.5·13.7·13.11을 따른다. 이 합의로 캠페인 활성 상태·총량·품절/재개 판단의 지연까지 포괄 승인한 것으로 해석하지 않는다. 현재 코드는 아직 기존 DB 조회 계약을 따른다.

**비용과 한계:** 기발급 결과 cache hit은 쿠폰 관련 조회/처리를 줄일 수 있으나 공통 사용자 존재 확인 DB 조회는 남는다. 미발급 대기자가 많은 요청에서는 positive 결과 캐시의 hit 이득이 작을 수 있다. 품절 표시가 오래 남았을 때 재고 변경/재개를 어떻게 처리할지는 별도 판단이 필요하다. 아직 DB/Redis 호출 감소나 지연 개선을 측정한 결과는 없다.

### 13.2 품절 캐시의 계층·적용 순서 — 합의

사용자는 품절을 먼저 Redis에 저장해 앱들이 공유할 수 있게 하고, 이후 로컬 캐시를 앞에 추가하는 순서를 선택했다. Redis를 생략하고 로컬에만 품절을 저장하는 제안은 채택하지 않는다. 로컬 확장 후에는 로컬에 품절 true가 있으면 품절 확인용 Redis 조회를 생략하고, 로컬 miss이면 Redis에서 확인하는 방향이다. 로컬을 Redis와 독립된 다른 품절 판정 기준으로 두지 않는다.

Redis부터 적용한 뒤 로컬을 추가하는 구현/비교 단계를 구분한다. 이 배치 선택으로 다중 인스턴스의 무효화·재시작 복구까지 설계/검증됐다는 뜻은 아니다. 기존 쿠폰 결과 우선과 공통 사용자 확인은 유지하므로 로컬 품절 hit이 모든 Redis/DB 호출을 없앤다고 설명하지 않는다. 로컬 채움 시점·각 TTL/운영 경계·장애 처리는 §13.3~13.11을 따른다.

### 13.3 품절 수명·운영 경계 — 합의

- 품절 캐시는 Redis 30초, 이후 확장할 로컬 5초를 초기값으로 한다. 관찰/비교를 위한 초기 선택이며 최적 TTL의 실측 결론은 아니다. 시작 설정으로 변경 가능하게 하고 자동 테스트는 더 짧은 값으로 별도 검사할 수 있다.
- 조회 hit으로 TTL을 연장하지 않는다. 로컬은 Redis에서 확인한 남은 수명보다 길게 품절을 보존하지 않는다. 남은 수명 확인/로컬 채움의 구체 처리와 경합은 §13.9·13.11을 따른다.
- 품절된 캠페인은 종료하며 같은 캠페인의 수량을 늘려 재개하는 기능은 이번 범위에서 제외한다. 추가 발급은 새 캠페인 ID로 진행한다. 키는 캠페인별로 구분한다. 이 선택은 기존 대기실의 캠페인 전환/진행 작업 종료·장애 복구 문제까지 해소한 것이 아니다.
- TTL이 있는 품절 캐시는 대기 순번/점유가 있는 기존 waiting meta 키 전체의 TTL로 구현하지 않는다. 새 캐시와 기존 품절 표시의 조회/채움 연결은 다음 상세설계에서 정한다.

### 13.4 캐시 오류와 대기실 상태 오류의 구분 — 합의

| 관찰 | 처리 원칙 |
|---|---|
| 캐시 조회 예외/timeout | 로그를 남기고 DB 조회로 보완한다. 캐시 오류만으로 신규 수용을 닫지 않는다 |
| 캐시 값의 형식 오류 | miss로 취급하고 DB에서 확인한다 |
| DB 조회 성공 뒤 캐시 저장 실패 | 이미 확보한 DB 응답을 유지한다 |
| 신규 발급 commit 확인 뒤 캐시 저장 실패 | 확정된 발급 결과를 유지한다. 쿠폰 취소/발급 수 감소/자동 재시도 없음 |
| 캐시 miss/실패 후 DB 조회도 실패 | 기존 DB 오류 처리를 따른다. 성공이나 품절로 임의 변환하지 않는다 |

캐시는 조회 비용을 줄이는 수단이며 대기 순번/실행 권한의 기준 저장소와 구분한다. 같은 Redis 서버/연결을 사용하더라도 캐시 오류의 DB fallback이 신규 발급 우회를 허용하지 않는다. 이후 대기실 register/find/start/processAdmissions/finish에서 실제 Redis 오류가 관찰되면 §12.12의 닫힘/결과 보존 규칙을 그대로 적용한다. 세부 호출 위치·로그/오류 변환·저장 순서와 재조회 횟수는 §13.9~13.10을 따른다.

### 13.5 기발급 결과의 배치·수명 — 합의

기발급 positive 결과는 Redis에 저장하고 초기 TTL은 30초로 한다. 조회 hit으로 TTL을 연장하지 않는다. DB에서 기존 결과를 읽었거나 신규 발급 commit과 응답 결과를 확인한 뒤 채우는 방향이며, 캐시에서 반환한 발급 응답은 기존 결과 200으로 처리한다. 이 합의는 미발급 false 캐시나 기발급 결과의 로컬 계층 추가를 뜻하지 않는다. 구체적인 키/값·적용 API·쓰기/후속 읽기별 채움 순서·동시 늦은 채움/이름 최신성 경계는 다음 상세설계에서 정한다.

**결과 미확보/종료 불명 예외 — 합의:** commit 확인은 됐지만 쿠폰 응답 값을 확보하지 못한 기존 분기에서는 캐시를 채우기 위한 추가 DB 조회를 하지 않고, 기존 오류/재요청 확인 계약을 유지한다. 후속 정상 요청이 DB에서 기존 결과를 읽으면 그때 채울 수 있다. DB 종료/commit 여부가 불명인 경우에는 기발급 성공 캐시를 쓰지 않고 기존 PROCESSING 점유 유지·신규 수용 닫힘 원칙을 적용한다. 서버가 commit과 응답 값을 확보한 뒤 HTTP 응답만 유실된 경우는 이 두 예외와 구분한다.

**정상 신규 발급 채움 순서 — 합의:** DB commit과 쿠폰 결과를 확인하고 기존 finish 처리를 마친 뒤, 이미 확보한 결과로 기발급 캐시를 저장한다. 캐시 채움을 위한 추가 DB 조회는 하지 않는다. finish 실패가 확정된 DB 결과를 취소하지 않는 기존 원칙과 캐시 저장 실패의 결과 보존 원칙을 유지한다.

### 13.6 등록 요청의 캐시 품절 우선순위 — 합의

대기 등록은 기존 쿠폰 결과 확인 → 운영 캠페인 설정/ID 일치·수용 열림 확인 → 품절 캐시 확인 순서로 한다. 품절 캐시가 true이면 캠페인 상태 DB 조회 없이 기존 COUPON_CAMPAIGN_SOLD_OUT/409를 반환한다. 품절이 확인된 뒤 캠페인이 INACTIVE가 되더라도 유효한 품절 캐시가 있으면 품절 오류를 우선하는 변경을 사용자가 승인했다. 캐시 miss/오류로 DB에 도달하면 기존 없음404·비활성409·품절409 순서를 유지한다. 기존 쿠폰이 있으면 이 품절 판단보다 앞서 반환한다.

이 합의는 대기 등록에 한정하며 GET·발급 POST·스케줄러의 품절 캐시 선행 적용을 승인한 것이 아니다. 발급의 PROCESSING/권한 오류 분기는 §13.7·13.10에 따라 기존 판단을 유지한다. 입구 RateLimiter/공통 사용자 확인은 기존처럼 Facade 앞에서 수행한다.

### 13.7 API별 적용 범위 — 합의

| 요청/작업 | 기발급 결과 캐시 | 품절 캐시 |
|---|---|---|
| 대기 등록 POST | 적용 | 적용 |
| 대기 조회 GET | 적용 | 미적용 |
| 발급 POST | 적용 | 미적용 |
| 보유 목록 GET | 미적용 | 미적용 |
| 자동 입장 처리 | 미적용 | 미적용 |

대기 조회는 기존 쿠폰 결과가 없으면 실제 대기 상태를 읽고, 발급은 기존 결과가 없으면 권한/PROCESSING과 DB 최종 판단을 유지한다. 품절 캐시를 앞세워 이 분기를 바꾸지 않는다. 스케줄러는 기존 캠페인 DB 조회·만료 회수/입장 배정을 유지한다. 목록의 페이지/시각/정렬·현재 이름 계약은 이번 기발급 캐시 적용 범위에서 제외한다. 구체적인 캐시 키/값·채움 지점은 §13.9~13.10을 따른다.

### 13.8 범위 마감·승인 상태

사용자는 Bulkhead를 이번 범위에서 제외하고 나머지 상세를 묶어 작성하도록 승인했다. 기존 등록 RateLimiter와 READY+PROCESSING 상한은 유지한다. 요청률 제한이 모든 DB 읽기/공유 자원을 격리한다는 뜻은 아니며 추가 Bulkhead/실행 풀은 만들지 않는다.

§13.1~13.13은 전체 정적 검토/보완본 재검토 후 사용자 최종 승인을 받았다. 제품 코드/테스트·장애/비교 실험의 실행 완료와는 구분한다. 구현은 단위별 범위를 따르며 실제 새 정책/계약 공백이 발견되면 해당 부분만 멈추고 사용자 판단을 받는다.

### 13.9 키·값·설정·책임 배치 — 승인 설계

#### Redis 키와 값

| 키 예시 | 값/범위 |
|---|---|
| coupon:issued:v1:42:7 | campaignId=42/userId=7의 기발급 결과 JSON. couponId, campaignId, campaignName, benefitType, benefitValue 다섯 필드 |
| coupon:sold-out:v1:42 | 캠페인42 품절 문자열 `true`만 저장 |

캠페인/사용자 ID는 검증된 Long을 십진 문자열로 키에 넣는다. 캐시를 HTTP 입력 키로 받지 않는다. adapter는 전용 엄격 JSON reader/mapper로 숫자 문자열·실수의 정수 변환 같은 관대한 coercion을 허용하지 않고, JSON 정수의 Long 범위·필수 필드/타입과 요청 campaignId 일치를 확인한다. 앱 공통 ObjectMapper의 설정은 바꾸지 않는다. 값의 불변식은 포트의 IssuedCoupon 생성자 한곳에서 검사한다. couponId/campaignId는 양수, 이름은 기존 캠페인 이름 범위이며 혜택은 같은 domain.coupon의 CouponBenefits를 재사용한다. adapter에서 같은 범위 검사를 복제하지 않는다. 누락/null/손상·알 수 없는 값은 miss/로그다. 임의로 만든 well-formed 잘못된 데이터의 진실성까지 검증하는 저장소는 아니며 키/값은 서버의 확정 DB 결과에서만 쓴다.

created/createdAt·waitingId·권한/순번·품절 false·미발급 표식은 기발급 JSON에 저장하지 않는다. 현재 목록 응답은 캐시하지 않으므로 발급 시각은 목록의 기존 DB 경로에 남긴다. 캐시 결과의 발급 응답은 항상 created=false/200이고 대기 응답은 ISSUED/couponId다. JSON을 API Entity나 application 응답 클래스로 직접 직렬화하지 않는다.

두 키 모두 TTL 필수다. 저장은 `SET ... NX PX ttlMillis`에 해당하는 기존 String template 연산으로 한다. 이미 키가 있으면 값/TTL을 바꾸지 않는다. 동일 positive 채움이 경합해도 하나가 먼저 채우고 나머지는 현재 항목을 덮어쓰거나 수명을 연장하지 않는다. malformed/no-TTL 키를 자동 삭제/수정하는 복구 로직은 추가하지 않는다. 그 키가 만료/별도 통제 정리되기 전 miss 경로의 비용이 남을 수 있다.

#### 설정과 타입

| 타입 | 책임/계약 |
|---|---|
| config/CouponCacheProperties | prefix commerce.coupon.cache. issued-ttl=30s, sold-out-ttl=30s. 로컬 추가 단위에서 local-sold-out-ttl=5s 추가. 생략 기본값. Redis 두 TTL은 toMillis()가 overflow 없이 1 이상이어야 한다. local TTL도 같은 밀리초 조건과 toNanos()의 양수/overflow 검사를 적용한다. null/형식/해당 변환 overflow는 기동 실패. Redis TTL에 불필요한 나노초 변환 제약은 두지 않는다. 시작 설정만 지원 |
| domain/coupon/CouponCacheRepository | findIssued(userId,campaignId): Optional<IssuedCoupon>, putIssuedIfAbsent(userId,campaignId,value,ttlMillis): void, findSoldOutTtl(campaignId): OptionalLong, putSoldOutIfAbsent(campaignId,ttlMillis): void. Spring/Redis/HTTP 타입 없음 |
| 포트 안 IssuedCoupon record | 위 다섯 필드. 기존 OwnedCoupon/신규 CouponIssueResult의 확보 값에서 변환, API 응답용 created는 포함하지 않음. 생성자에서 ID/이름/혜택 불변식 검사, adapter와 중복하지 않음 |
| infrastructure/coupon/CouponCacheRedisRepository | bean couponCacheRedisRepository. 기존 master String template·JSON 변환·키 조립·단일 키 읽기 Lua·NX/TTL 저장. get/put 기술 오류는 예외로 상위에 전달하고 정상 없음과 구분 |
| application/coupon/CouponCacheService | bean couponCacheService. cache-only 조회/저장 실패를 로그/miss 또는 무시로 변환. DB·쿠폰 수용 닫기·TX는 담당하지 않음. 로컬 품절 슬롯은 이 객체 내부에 추가 |
| 기존 CouponFacade | 캐시 조회→기존 DB 확인·TX 종료→채움과 기존 업무 순서 조율. 목록/스케줄러는 캐시 미적용. 새 Facade·별도 전달 서비스 없음 |

서비스 메서드는 findIssued(user,campaign): Optional<IssuedCoupon>, rememberIssued(user,campaign,result: CouponIssueResult): void, isSoldOut(campaign): boolean, rememberSoldOut(campaign): void로 한다. rememberIssued는 확보한 application 결과를 받아 try 안에서 포트의 IssuedCoupon으로 변환한다. 값 변환 실패도 저장 실패로 처리해 확정 업무 응답을 보존한다. false는 '캐시에서 품절 확인 못함'이지 DB 재고 있음 판정이 아니다. Redis 예외/timeout·변환 오류는 RuntimeException 범위에서 로그로 남기고 캐시 miss/저장 실패로 처리한다. Error를 삼켜 성공을 꾸미지 않는다. 실패 시 동일 작업 retry·새 회로/풀·캐시 실패 전역 상태는 만들지 않는다. 캐시 get/put 실패 때문에 같은 요청에 여러 timeout 비용이 남을 수 있으며 별도 격리/성능 보장을 주장하지 않는다.

새 제품 책임은 설정1·포트1(중첩 값)·Redis 구현1·application 캐시 서비스1·읽기 Lua1로 제한한다. 로컬용 별도 bean/설정 클래스·라이브러리·공용 캐시 프레임워크는 추가하지 않는다. 기존 CouponAdmissionRepository/Lua·RedisConfig·등록 limiter·scheduler·DB 모델/제약은 변경하지 않는다.

#### 읽기 Lua

resources/redis/coupon-cache-read.lua는 KEYS1개/ARGV없음, 원자적으로 GET/PTTL을 읽는다. 기존 대기실 Lua와 결합하지 않는다.

```text
인수 수와 키 타입(none/string)을 확인
none이면 [MISS, 빈 문자열, 빈 문자열]
string이면 value=GET, remaining=PTTL
  value 없음 또는 remaining<=0이면 MISS  // 영구키/PTTL -1도 hit으로 쓰지 않음
  그 외 [HIT, value, remaining의 십진 문자열]
타입/인수/설명 불가 응답은 기술 오류, 쓰기/기한 연장/삭제 없음
```

adapter는 문자열 배열 길이/결과명/잔여값·PTTL 양수/표현 범위를 검사한다. issued HIT은 JSON, sold-out HIT은 정확히 `true`만 허용한다. findSoldOutTtl은 남은 밀리초만 반환한다. 기본 Redis TTL 키를 읽기 hit마다 갱신하지 않는다. 기발급도 TTL 없는 키는 miss라는 조건을 유지하므로 단독 GET으로 바꾸지 않고 같은 읽기 Lua를 공유한다.

### 13.10 조회·채움·TX 순서 — 승인 의사코드

#### 기발급 결과의 최초 확인과 강제 DB 재확인

```text
findExistingForRequest(user,campaign):
  cached=cacheService.findIssued(user,campaign)    // 실패는 Optional.empty
  있으면 반환                                   // 결과 필드, created=false
  owned=readTemplate.execute: 기존 findOwned/값 복사
  // template 정상 종료 뒤만 아래 실행. 없음/읽기 예외는 채우지 않음
  owned 있으면:
    value=다섯 필드 복사
    cacheService.rememberIssued(user,campaign,value)
    return value
  return 없음
```

registerWaiting/getWaiting/issue의 첫 기존 결과 확인만 이 경로로 바꾼다. OwnedCoupon에 가짜 createdAt을 넣거나 Entity를 캐시하지 않는다. 캐시용 중첩 값에서 기존 CouponIssueResult(created=false)·ISSUED snapshot을 조립한다.

start의 PROCESSING/ISSUED 또는 등록/조회 Lua의 ISSUED 뒤 기존 한 번 DB 재확인은 캐시로 대체하지 않는다. 직접 DB를 읽고 정상 종료 뒤 positive가 있으면 채운다. ISSUED 후 DB에도 없으면 기존500/닫힘, PROCESSING 후 없으면 기존202다. 쓰기 TX 안의 resolveNotReserved/storedAfterConflict 등 기존 DB 결과 판정에도 캐시 get/put을 넣지 않는다. 해당 경로의 positive 채움은 아래처럼 모든 DB 호출/finish 이후에 한다.

#### 신규 발급

```text
issue의 첫 기존 결과 확인=위 함수
없으면 기존 헤더→운영 ID/열림→start 순서
STARTED만 기존 issueStarted 수행
  기존 쓰기/후속 읽기 TxCall 판정 재사용
  UNKNOWN/완료 통지 없음: PROCESSING 유지·닫힘, 캐시 저장 없음
  종료 확인: 기존 finish(ISSUED/FAILED), 실패해도 확정 결과 보존
  오류 응답이면 원래 오류 전파
  반환 가능한 확정 쿠폰 결과가 있으면 rememberIssued
  기존 신규201/기존200 반환
```

commit 확인/응답 값 미확보는 기존500과 finish(ISSUED)를 유지하되 캐시용 재조회/저장은 하지 않는다. 미시작/rollback/후속 읽기 실패에서 positive 결과가 없으면 채우지 않는다. 캐시 저장을 finally의 무조건 작업으로 넣지 않는다. commit/반환 오류로 응답 값이 확보되지 않은 것을 임의 성공 처리하지 않는다. 캐시 I/O 동안 DB TX/연결·PROCESSING 점유를 일부러 유지하지 않는다. 이미 다른 요청이 완료한 결과의 DB 읽기 채움은 해당 읽기 TX 종료 뒤 수행한다.

#### 품절 등록

```text
registerWaiting:
  입구 limiter→기존 사용자/인자 검증
  기존 결과=기발급 cache→miss이면 DB, 있으면 ISSUED/200
  기존 운영 ID/수용 조건 확인
  cacheService.isSoldOut(campaign)이 true이면 품절409
  check=짧은 readTemplate에서 캠페인 존재/비활성/품절/발급가능을 값으로 반환
  // Entity를 밖으로 내보내지 않음. DB 읽기 정상 종료 뒤 판단
  MISSING:404 / INACTIVE:409, 품절 캐시 쓰지 않음
  SOLD_OUT:rememberSoldOut→품절409
  ISSUABLE:기존 register Lua·생성/재사용/ISSUED 경합 처리
```

캠페인 판단은 Facade의 작은 private enum/값으로 충분하며 새 공개 서비스/클래스를 만들지 않는다. DB 읽기가 실패/종료 불명인데 원인 체인에 품절 신호가 있다는 이유로 캐시를 쓰지 않는다. DB 정상 종료 뒤 실제로 확인한 품절만 채운다. 스케줄러/발급 POST에서 품절 캐시를 읽거나 갱신하지 않는다. Redis register의 SOLD_OUT 결과만으로 새 캐시를 채우는 경로도 추가하지 않는다.

### 13.11 로컬 품절 확장·동시성·한계 — 승인 설계

로컬은 품절에만 적용한다. 현재 신규 등록은 requireAdmitting 이후 설정된 단일 운영 campaignId만 도달하므로, CouponCacheService 내부 AtomicReference<LocalSoldOut> 한 슬롯이면 충분하다. 값은 campaignId·단조 시계 기준 보관 시점·유효 나노초다. Map/최대항목수 설정·별도 청소 thread/eviction 프레임워크는 두지 않는다. ID 불일치/기한 경과는 miss, 새 ID 채움은 슬롯 교체다. 과거 캠페인 기발급 결과는 Redis 키별 조회로 유지한다. 다중 운영 캠페인 요구가 생기면 이 구조를 재검토한다.

```text
isSoldOut(campaign):
  로컬 슬롯의 ID가 맞고 elapsed<유효기간이면 true (Redis 호출0)
  started=System.nanoTime()
  remainingMillis=Redis findSoldOutTtl(campaign)   // 오류/없음이면 false, 로컬 저장 없음
  now=System.nanoTime()
  budget=remainingMillis의 나노초 - (now-started)
  budget<=0이면 false                           // 응답/처리 중 기한 소진
  localLifetime=min(localSoldOutTtl.toNanos(),budget)
  (campaign,now,localLifetime)를 슬롯에 저장
  return true
```

로컬 채움 전까지의 실제 경과를 budget에서 차감한다. Redis의 PTTL 관찰 시점보다 앞선 요청 시작부터 차감하는 보수적 방식이므로 통신 지연을 남은 수명에 더하지 않는다. 나노초 변환/계산 overflow는 miss/로그로 처리한다. 읽기/쓰기/로컬 hit에서 TTL을 연장하지 않는다. 지연된 동시 응답도 자기 PTTL과 경과를 다시 적용하며, 슬롯을 교체했더라도 관찰한 Redis 수명을 넘지 않는다. 상대 수명 판정은 정상 시계 진행 전제이며 임의 Redis 시간 역행/강제 키 삭제의 즉시 로컬 무효화까지 보장하지 않는다.

rememberSoldOut의 DB 확인 뒤 NX 저장은 Redis에만 한다. 로컬은 다음 요청이 실제 Redis true/PTTL을 읽었을 때 채운다. Redis 저장 timeout 후 적용 여부를 추측해 로컬에 직접 true를 넣지 않는다. 로컬 hit이면 서버 Redis 장애가 있어도 품절 확인을 위해 Redis에 재접속하지 않는다. 기존 쿠폰 확인/사용자 DB는 앞에서 별도로 수행하므로 전체 요청의 외부 I/O0이나 DB 장애에서도 항상 기존 결과 제공을 보장하지 않는다.

#### B단위 내부 처리 구체화 — 정적 검토·사용자 최종 승인 완료

제품 변경은 CouponCacheProperties·CouponCacheService 두 파일이다. 설정 record에 localSoldOutTtl을 마지막 인자로 추가하고 기본5s·기존 밀리초 검사·추가 toNanos overflow/양수 검사를 적용한다. Redis 두 TTL에는 나노초 제약을 추가하지 않는다. 서비스는 설정을 유효 나노초로 복사하고 AtomicReference<LocalSoldOut> 하나를 가진다. LocalSoldOut은 서비스 private 중첩 record(long campaignId, long observedAtNanos, long lifetimeNanos)이며 별도 bean/파일·청소 기능·Map을 추가하지 않는다. 기발급/저장 경로·포트·adapter/Lua·Facade·공통 설정은 변경하지 않는다.

isSoldOut의 local 판정은 ID의 숫자 값 일치와 0 <= now-observedAtNanos < lifetimeNanos다. boxed Long끼리 참조를 비교하지 않는다. 다른 ID·만료·설명 불가 경과는 Redis 경로로 간다. 만료 슬롯을 조회만으로 새 수명으로 갱신하지 않는다. Redis 조회 시작 시각부터 응답 변환/채움 판단까지 경과를 빼고, Math.multiplyExact(remainingMillis, 1_000_000) 등 overflow 검사를 거친 뒤 min(local 설정,budget)을 저장한다. OptionalLong 없음/0 이하·음의 경과·budget<=0·변환 overflow·RuntimeException은 false/미채움이며 기존 오류 로그 원칙을 따른다. Redis 조회 오류와 로컬 수명 계산 오류를 모두 다룬다는 의미가 드러나도록 로그를 작성한다. 한 catch를 쓰면 ‘품절 캐시 조회/수명 판정 실패’처럼 표현해 계산 overflow를 Redis 장애로 단정하지 않는다. Error는 기존처럼 전파한다. 관찰 시각은 채움 판단에서 확보한 값을 슬롯에 그대로 쓰며 슬롯 저장 뒤 새 시각으로 수명을 다시 시작하지 않는다.

슬롯은 불변 값 하나를 원자 교체한다. 동시에 miss한 요청이 Redis를 각각 조회하는 것은 허용하며 single-flight/전역 잠금/CAS 재시도는 넣지 않는다. 늦은 응답도 자신의 잔여 수명과 전체 조회 경과를 적용한다. 경합 중 다른 요청이 더 최근 슬롯을 넣었더라도 각 응답의 유효 범위만 보장하고 최신 슬롯 보존 순서를 새 계약으로 추가하지 않는다. miss/오류 뒤 기존 슬롯을 억지 연장하거나 다른 ID에 재사용하지 않는다. rememberSoldOut은 계속 Redis 저장만 하며 성공/실패와 무관하게 local을 직접 채우지 않는다.

검사는 서비스 사례마다 새 인스턴스로 시작한다. 시간 경계는 짧은 test TTL·실제 단조 시각/유한 대기·저장소 지연 대역으로 확인하고 제품 clock/reset hook을 추가하지 않는다. 기존 서비스의 true 다음 false 전달 검사는 local 유효 hit과 별도 새 인스턴스의 순수 miss로 나눠 검증 의미를 보존한다. 실제 Redis/Facade 검사에서는 관찰용 저장소 decorator의 호출 수와 DB SQL을 함께 기록한다. 다른 ID의 한 슬롯 교체 검사는 서비스 내부 캐시 구분만 확인하며 다중 운영 캠페인 지원을 뜻하지 않는다.

이름 갱신·재고 재개·손상 데이터에 대한 경계:

- 이름 변경은 캐시 결과의 짧은 지연을 허용한다. TTL30초는 저장 이후 수명이며 DB 읽기 시점부터의 엄격한 최신성 상한이 아니다. 변경 전에 시작한 조회가 늦게 채울 수 있다. NX로 유효한 새 항목 덮어쓰기를 줄이되, 만료 뒤 늦은 최초 채움까지 막는 버전/락 프로토콜은 넣지 않는다. 엄격한 변경 시각 기준 상한이 필요하면 별도 승인으로 재검토한다.
- 같은 캠페인의 수량 증대/재개·쿠폰 취소/삭제는 이번 제품 유스케이스가 아니다. TTL 만료를 캠페인 재개로 해석하지 않는다. 테스트가 이름/DB 상태를 직접 바꾸는 경우 cached 경로와 DB 경로 기대를 구분한다. 목록/직접 QueryRepository는 기존 현재 이름/손상 감지 검사를 유지한다.
- 유효한 positive 캐시가 있으면 해당 요청은 원본 DB/캠페인 손상을 즉시 재검사하지 않는다. 캐시 miss 후에는 기존 LEFT JOIN 손상 감지/500을 유지한다. 성공 캐시를 매번 DB에서 다시 검증하는 방식은 채택하지 않는다. 이 제한을 명시하고 원본 삭제/취소 기능을 후속에 도입하면 무효화를 함께 설계한다.
- 원시 Redis 수동 삭제/이전 DB 폐기·ID 재사용을 일반 제품의 정상 갱신으로 보지 않는다. 검사에서는 §13.12의 test 격리 규칙을 따른다. 제품 cleanup API·캐시 flush endpoint는 추가하지 않는다.

### 13.12 검증 기준·기존 검사 전환 — 전부 실행 전

| ID | 필수 근거/관찰 |
|---|---|
| KQ1 | 설정 기본30s/30s/5s·정상 변경값·양수/형식/밀리초 overflow 및 local TTL 나노초 overflow 기동 거절, Properties scan·추가 스위치 없음 |
| KQ2 | 서로 다른 campaignId/userId로 실제 키 문자열/값 연결·인자 순서를 단언. 실제 fixture master Redis의 5필드 JSON·엄격 숫자 타입/범위·true/PTTL·양수 TTL·TTL 없는 키 miss, NX 동시 최초 채움/유효 항목 비덮어쓰기·조회/중복 put TTL 비연장·만료 뒤 miss/재채움 |
| KQ3 | 등록/대기 GET/발급의 positive hit은 쿠폰 DB/대기 상태 경로 미호출, miss는 DB 확인·없음 미캐싱, 기존 결과200/ISSUED·품절/닫힘/운영 ID보다 우선. 공통 사용자 조회는 유지 |
| KQ4 | 실제 commit/TX 종료·finish 이후 저장, cached created=false/200, 정상 읽기 TX 종료 후 채움, rollback/UNKNOWN/응답 값 미확보 미기록, finish 오류·캐시 put 오류도 확정 결과 유지. HTTP 응답 유실 뒤 캐시/DB 기존 결과로 추가 효과0 |
| KQ5 | 등록에서 기발급/운영 ID/열림→품절 순서, cached 품절은 캠페인 DB 조회0·409(뒤 INACTIVE도 품절 우선), miss DB 없음/비활성/품절 순서·정상 읽기 종료 뒤 true 채움. GET/발급/목록/scheduler 품절 캐시 미적용 |
| KQ6 | 캐시 get timeout/형식·잘못된 키/JSON/TTL은 DB fallback·수용 유지, put 오류 결과 보존·재시도0, DB 오류 원래 전파, 이후 대기실 Redis 실패는 기존503/닫힘. 실제 오류와 대역을 구분 |
| KQ7 | 실제 Redis true/PTTL에서만 local 채움·local hit의 품절 Redis 호출0·5초/상위 잔여 중 짧은 수명·조회 비연장·초기 Redis put 실패 local 미채움·ID 분리/한 슬롯 한계. 짧은 테스트 TTL·지연된 응답 대역과 실제 Redis 검사를 구분 |
| KQ8 | PROCESSING/ISSUED 뒤 강제 DB 한 번 재확인과 기존500/닫힘 유지·DB 쓰기 TX 내 캐시0·추가 캐시용 DB 조회0, 캐시 손상/동시 채움과 늦은 이름 채움의 명시된 범위 확인 |
| KQ9 | 기존 DB 총량/UNIQUE/TX·WQ1~WQ12의 유효 검증 유지. 직접 SQL/이름/손상 검사는 raw DB 또는 캐시 miss 준비로 의도를 보존하고 warm-cache 이름 지연/만료 뒤 갱신 검사를 추가. 기대 삭제/완화 금지 |
| KQ10 | Checkstyle/ArchUnit·기존 HTTP/준비/자동 scheduler·W6/O11 추가 bean 격리·W7 lifecycle, 자기 캐시/로컬 소유 상태만 종료 확인 뒤 정리. 실패/skip/미실행과 과거 실행을 구분 |

W6/O11 CouponBeanIsolation에는 새 동작 bean couponCacheRedisRepository·couponCacheService를 도입 단위에서 함께 추가한다. 동작 없는 CouponCacheProperties는 제외하지 않는다. 캐시 서비스의 로컬 확장은 추가 bean을 만들지 않는다. 기존 관리 타입/DDL/행 보존·이름 부재 기동 거절은 유지한다.

기존 Spring 기반 쿠폰 E2E·HTTP fault·rate limit·scheduler·로컬 준비/opt-in 장애 컨텍스트는 공통 test 전용 CouponCacheRepository를 @Primary로 조립한다. 이 대역은 두 조회를 항상 empty로 반환하고 두 저장은 no-op이며 실제 Redis에 접근하지 않는다. 로컬 슬롯은 Redis true 조회에서만 채워지므로 기존 컨텍스트에서 로컬도 비어 있게 유지한다. 앱을 직접 시작하는 로컬 준비 검사는 해당 test 구성을 명시적으로 추가한다. 일반 Service/Facade/TX 직접 조립 검사도 같은 miss/no-op 계약을 기본으로 사용한다. DB truncate로 AUTO_INCREMENT가 초기화돼 같은 ID가 재사용돼도 이전 사례의 캐시가 다음 사례를 가로채지 않게 한다.

실제 캐시를 검사하는 KQ 컨텍스트에는 이 대역을 넣지 않는다. fixture ID를 만든 뒤 첫 캐시 요청 전에 사용할 campaign×user의 issued 키와 sold-out 키가 모두 비었는지 확인한 경우에만 소유한다. 준비 실패 시 남의 키를 삭제하지 않고, 서버/worker/실행기 종료가 확인된 뒤에만 소유 키를 정리한다. KQ7 로컬 수명 검사는 직접 조립한 새 CouponCacheService 인스턴스로 시작하며 다른 검사와 슬롯을 공유하지 않는다. 실제 HTTP 캐시 통합 검사가 local을 포함하면 사례별 새 서비스/컨텍스트로 같은 격리를 확보한다.
 실제 cache/DB/Redis 관찰 없이 대역을 성능/부하 근거로 쓰지 않는다. TX 대역 검사는 기존 afterCompletion 분기를 유지하고 매번 cache가 앞단에서 가로채 원래 분기가 실행되지 않는 허위 통과를 막는다. 기존 HTTP 응답 유실/서버 종료·worker 정리 보류·OwnedWaitingKeys/ExecutorSettlement를 유지한다. 캐시 key 정리에는 별도 소유 확인이 필요하며 다른 사용자 키 전체를 FLUSHALL로 지우지 않는다. 로컬 격리는 위의 새 서비스/컨텍스트 조립으로 확보하고 제품 reset hook은 추가하지 않는다.

### 13.13 후속 검증 경계

KQ는 캐시의 정합성·오류 처리 기준이며 실제 장애 복구·성능 측정 완료를 뜻하지 않는다. 공유 Redis pause·늦은 명령·수동 재개·재시작은 후속 검증 대상이다. 캐시 비교에서는 사용자 존재/기발급 miss의 DB 조회와 scheduler의 기존 DB 조회 비용을 포함하고, 거절 증가를 정상 처리 성능 개선으로 해석하지 않는다. 현재 캐시 구현·측정 결과는 없다.
