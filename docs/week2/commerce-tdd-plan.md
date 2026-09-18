# 커머스 TDD 계획

[전체 설계](commerce-erd-draft.md) · [API 계약](commerce-api-contract.md) · [정책 선택과 설계 검토](commerce-policy-decisions.md)

재고·사용자 식별·브랜드·주문 수량 규칙과 API-01~24의 기대값, 작은 기능의 구현 순서를 다룬다. 각 절은 도메인 단위 검증, application·실제 DB 연결, HTTP 완료를 구분한다. 최신 완료 증거와 전체 검사 결과는 [전체 완료 체크리스트](commerce-completion-checklist.md)에 모으고, 실제 Red·Green·Refactor는 기능별 실행 기록에 남긴다. 응답·금액·잠금 정책은 후속 추천 묶음 승인(P08·P12·P13)으로 확정했으며 아래 기대값으로 검증한다.

## 전체 구현 완료 목표

2026-09-18 사용자가 현재 구상한 범위의 TDD를 끝까지 진행하도록 요청했다. 작은 증분 하나의 통과를 전체 완료로 간주하지 않는다. [전체 완료 체크리스트](commerce-completion-checklist.md)에 API·테스트·운영 검증의 증거를 기록한다. 최종 범위는 C01~C12·A01~A13의 25개 API, 아래 API-01~24의 업무·경계·저장·동시성 사례, 실제 DB 제약·운영 스키마, 기존 예시 회귀와 Checkstyle·ArchUnit, 구현과 일치하는 문서다.

진행 순서는 브랜드 HTTP → 관리자 브랜드·상품 → 상품 조회·좋아요 → 포인트 → 주문 생성·조회·확정·관리자 조회 → 경합·롤백과 최종 검증이다. 테스트를 먼저 실행해 의도한 실패를 확인한 뒤 최소 구현·리팩터링을 반복한다. C01 이후 금액·나머지 API·잠금 세부 정책도 사용자 승인으로 확정했다. JPA를 유지하며 기능 구현을 먼저 진행하고 나머지 운영 수동 DDL은 기능 구현 후 작성·검증한다(P14).

## 현재 단계와 이번 구현

| 단계 | 현재 상태와 증거 |
| --- | --- |
| 1. 재고 차감·최종 수량 설정 | ProductStock 규칙을 Product의 생명주기·JPA·관리자 재고 API·주문 차감에 연결했다. [상품 기록](commerce-tdd-product-log.md) |
| 2. 사용자 식별·권한 | 단일 fixture, DB 초기 잔액 0과 재실행 보존, 본인·관리자 진입 검사 완료. [포인트·좋아요 기록](commerce-tdd-point-like-log.md) |
| 3. 브랜드 CRUD | 고객 상세와 관리자 전체 CRUD, 실제 상품 존재 조회·잠금·경합을 연결했다. [브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md) |
| 4~7. 상품·좋아요·포인트 | 변경·조회·집계·페이지·현재 관계·충전과 잔액 조회를 HTTP까지 검증했다. [상품](commerce-tdd-product-log.md) · [포인트·좋아요](commerce-tdd-point-like-log.md) |
| 8~10. 주문 | 생성·스냅샷·본인/관리자 조회·확정·재요청·동시성·실제 flush 후 롤백을 검증했다. [주문 기록](commerce-tdd-order-log.md) |

**25개 API가 HTTP부터 실제 DB까지 연결됐다.** 테스트 수나 API 수를 전체 개발 완료율로 환산하지 않는다. 최종 전체 회귀·Checkstyle·ArchUnit·수동 SQL·문서 검증은 [완료 체크리스트](commerce-completion-checklist.md#최신-통합-검사)에 실제 실행 결과를 기록한다.

Layer-first·빌드 모듈·기존 ArchUnit 규칙을 유지한다. application은 domain의 약속을 사용하며 infrastructure 구현이나 controller DTO를 참조하지 않는다. HTTP 오류는 커머스 경로의 공통 처리기로 변환하고 Example 계약은 유지한다. 실습 fixture를 사용하며 로그인은 추가하지 않는다.

아래는 작은 도메인 규칙에서 시작해 연결한 **단계별 기대값과 선행 증거**다. 과거 단계의 테스트 수를 최신 전체 검사 수로 사용하지 않는다. 정책은 P00~P15의 확정 계약을 따른다. 일반 Spring 테스트는 ddl-auto=create, 운영 SQL 검증은 별도 MySQL에서 수동 적용 후 validate를 사용한다.

## 대표 도메인 규칙의 TDD 계획

대표 규칙은 **상품 재고보다 많은 수량을 차감할 수 없으며, 성공하면 남은 재고가 정확해야 한다**다. 상품 자신의 상태와 차감 수량으로 판단하므로 `Product` 책임을 제안한다. 첫 구현은 상품이 소유할 `ProductStock` 객체에 수량 규칙을 둔다. 이 선행 단계 이후 상품 엔티티·삭제 상태(STOCK-06)와 API를 연결했으며 증거는 위 현재 상태와 기능별 실행 기록에 구분한다.

| 사례 ID | 초기 상태·입력 | 도메인 단위 테스트 기대값 | API 연결 |
| --- | --- | --- | --- |
| STOCK-01 | 미삭제 상품, 재고 5, 2개 차감 | 성공, 재고 3 | C10 성공 시 상품 상태 변경 |
| STOCK-02 | 미삭제 상품, 재고 5, 6개 차감 | 재고 부족 오류, 재고 5 유지 | C10의 `409 INSUFFICIENT_STOCK` |
| STOCK-03 | 재고 5, 5개 차감 | 성공, 재고 0 | 정확히 재고만큼 주문 확정 가능 |
| STOCK-04 | 재고 0, 1개 차감 | 재고 부족 오류, 재고 0 유지 | 품절 상품 주문 확정 거절 |
| STOCK-05 | 재고 5, 0 또는 -1개 차감 | 양수 수량 규칙 위반, 재고 5 유지 | C09의 입력 오류는400, C10의 저장 수량 불변식 위반은500으로 구분 |
| STOCK-06 | 삭제 상품, 재고 5, 2개 차감 | 삭제 상태 오류, 재고 5 유지 | C10의 `404 PRODUCT_NOT_FOUND` |

이번 확장은 `ProductStock`의 **0 이상인 최종 수량 설정**이다. 증감량을 더하지 않고 현재 수량을 교체하며, 잘못된 수량은 변경 전에 거절한다. 상품 엔티티·관리자 API보다 이 수량 규칙을 먼저 구현하는 순서는 사용자 승인 범위다.

| 사례 ID | 초기 상태·입력 | 도메인 단위 테스트 기대값 | 후속 연결 |
| --- | --- | --- | --- |
| STOCK-SET-01 | 재고 10에서 최종 수량 3 설정 | 재고 3. 기존 수량에 더하지 않음 | A11·API-19 |
| STOCK-SET-02 | 양수 재고에서 최종 수량 0 설정 | 재고 0 허용 | A11·API-19 |
| STOCK-SET-03 | 기존 재고에서 -1 또는 `Integer.MIN_VALUE` 설정 | 잘못된 재고 수량 오류, 기존 수량 유지 | A11·API-19의 오류 변환은 별도 검증 |
| STOCK-SET-04 | `Integer.MAX_VALUE`, 현재보다 큰 수량, 현재와 같은 수량 설정 | 각각 입력한 최종 수량을 정확히 유지 | A11·API-19. Integer 초과 HTTP 입력 검증은 별도 |
| STOCK-SET-05 | 수량 설정 후 차감, 차감 후 수량 설정 | 두 동작이 같은 현재 수량을 사용하고 각각의 규칙을 유지 | A11·C10의 상품 연결·경합도 상품/주문 통합 테스트에서 검증 |

도메인 테스트는 HTTP 상태를 반환하도록 엔티티를 만들지 않는다. 도메인 실패를 위 응답으로 변환하는 책임은 API 계층이고, 그 연결은 별도 API 테스트에서 검증한다.

1. **Red:** 먼저 STOCK-01·02의 정상·대표 오류 기대값을 테스트로 작성한다. 최소 타입·메서드 골격을 준비하고, 의도한 재고 상태 또는 부족 검사가 없어 실패하는지 확인한다. DB 접속·환경 설정 실패를 업무 규칙의 Red로 기록하지 않는다.
2. **Green:** 해당 테스트를 통과시키는 최소 재고 검사·차감만 구현한다. 변경 전에 검증하여 실패 시 재고를 유지한다.
3. **Refactor:** 중복 검사·테스트 준비 코드를 정리하고 이름·책임을 다듬는다. 같은 테스트를 재실행해 기대값이 유지되는지 확인한다.
4. STOCK-03~05와 STOCK-SET-01~05를 한 사례씩 추가하며 같은 사이클을 반복한다. STOCK-06은 상품 생명주기와 연결할 때 구현한다. 테스트나 기대값을 삭제·완화해서 통과시키지 않는다.

[ProductStockTest](../../apps/commerce-api/src/test/java/com/loopers/domain/product/ProductStockTest.java)는 STOCK-01~05와 STOCK-SET-01~05, 초기 재고·정수 경계·연속 차감을 검증한다. 파일명 자체는 요구사항이 아니다. 아래 명령의 실제 실패·성공 이유와 리팩터링 결과는 [실행 기록](commerce-tdd-stock-log.md)에 남긴다.

```shell
./gradlew :apps:commerce-api:test --tests '*ProductStockTest'
```

상품 한 개의 수량 단위 테스트는 상품 엔티티의 삭제 상태, 관리자 권한·HTTP 계약, DB 행 잠금이나 주문 전체의 원자성·동시성을 증명하지 않는다. 이 연결은 상품/주문 통합 테스트에서 별도로 검증했다. C10의 여러 상품·잔액·주문 상태와 동시성은 OrderConcurrencyIntegrationTest와 주문 HTTP/저장 테스트가 증거다.

## 사용자 식별·권한 해석의 TDD 계획

선행 사용자 식별 TDD에서는 application의 `UserResolver`가 domain 소유 `UserIdentityRepository`를 통해 외부 ID를 내부 사용자 ID·역할(`UserIdentity`, `UserRole`)로 해석하고 관리자 여부를 확인하는 것이다. 공통 규칙은 조회 대역으로, 확정한 매핑은 실제 fixture와 Spring 연결로 검증한다. 실패는 HTTP와 독립된 `UserResolutionException` 사유로 구분한다.

| 사례 ID | 초기 상태·입력 | 단위 테스트 기대값 |
| --- | --- | --- |
| IDENTITY-01 | 조회 대역에 등록된 외부 ID로 사용자 해석 | 연결된 내부 사용자 ID와 역할 반환 |
| IDENTITY-02 | 조회 대역에 없는 외부 ID로 사용자 해석 | 사용자를 찾을 수 없는 사유로 거절 |
| IDENTITY-03 | `null`, 빈 문자열, 공백뿐인 외부 ID로 사용자 해석 | 잘못된 식별 입력 사유로 거절하고 repository를 조회하지 않음 |
| IDENTITY-04 | ADMIN 사용자의 외부 ID로 관리자 검사 | 해석한 관리자 식별 정보 반환 |
| IDENTITY-05 | CUSTOMER 사용자의 외부 ID로 관리자 검사 | 권한 부족 사유로 거절 |
| IDENTITY-06 | 잘못된 입력 또는 미등록 외부 ID로 관리자 검사 | IDENTITY-03·02의 식별 실패 사유를 유지하며 권한 부족으로 바꾸지 않음 |
| IDENTITY-07 | 실제 fixture에 alice·bob·admin 조회 | 각각 1/CUSTOMER·2/CUSTOMER·3/ADMIN 반환 |
| IDENTITY-08 | 실제 fixture에 unknown·문자열 1·2·3 조회 | 매핑 없음. 내부 숫자 ID를 외부 식별값으로 대체하지 않음 |
| IDENTITY-09 | Alice·ADMIN 또는 앞뒤 공백이 붙은 alice·admin 조회 | 원문이 다르므로 매핑 없음. trim·대소문자 변환 없음 |
| IDENTITY-10 | Spring이 fixture 구현을 UserResolver에 주입 | 확정 매핑·관리자 역할 검사·원문 비교가 조립 후에도 유지 |

P03의 후속 답변으로 고정 실습값·역할과 원문 일치 정책을 확정했다. `FixtureUserIdentityRepository`가 불변 Map 한 곳에서 매핑을 관리한다. 기존 조회 대역의 임의 값은 이 실습 매핑과 구분한다. DB 사용자 행·잔액·HTTP 진입점은 후속 [포인트·좋아요 TDD](commerce-tdd-point-like-log.md)에서 연결·검증했다.

이 단위 테스트만으로 **API-21 전체 완료를 뜻하지 않는다**. 관리자 HTTP 경로는 후속 P15에 따라 fixture 역할 대신 Spring Security 역할을 사용하며, 해당 필터·CSRF 연결은 [관리자 Security 기록](commerce-admin-security-log.md)에서 검증한다. 관리자 경로의 권한 검사를 HTTP 입력 바인딩보다 먼저 실행하는 P10 연결과 구체 오류 응답·헤더 처리는 별도로 검증한다. 현재 `UserResolver`와 fixture 구현은 Spring 컴포넌트로 등록하고 domain 조회 계약을 생성자로 주입한다. 실제 Red·Green·Refactor 결과는 [사용자 식별 실행 기록](commerce-tdd-user-identity-log.md)에 남긴다.

## 브랜드 이름의 TDD 계획

P02에서 확정한 브랜드 이름 규칙을 `domain/brand/BrandName` 불변 값객체가 담당한다. 이름 생성 시 앞뒤 공백을 제거하고 결과가 Unicode 코드 포인트 기준 1~100자인지 검사한다. 실패는 HTTP와 독립된 `BrandNameException` 사유로 구분한다. 브랜드 이름의 선행 승인과 구분하여, 상품명의 같은 계산 방식도 후속 P02 묶음 승인으로 확정했다.

| 사례 ID | 입력 | 도메인 단위 테스트 기대값 |
| --- | --- | --- |
| BRAND-NAME-01 | 앞뒤 공백·탭·개행이 붙은 `Loopers  Brand` | 앞뒤 공백만 제거하고 내부 공백·대소문자 유지 |
| BRAND-NAME-02 | null·빈 문자열·공백만 있는 문자열 | EMPTY_NAME으로 거절 |
| BRAND-NAME-03 | 앞뒤 공백이 붙은 한글·이모지 각각 1개 또는 100개 | 공백 제거 후 원문 유지. 😀 하나를 1자로 계산 |
| BRAND-NAME-04 | 한글·이모지 각각 101개 | NAME_TOO_LONG으로 거절 |

4개 규칙의 13개 실행 사례를 [BrandNameTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandNameTest.java)로 검증했다. 실제 실패·통과와 리팩터링은 [브랜드 이름 실행 기록](commerce-tdd-brand-name-log.md)에 남긴다. 이름 규칙을 `Brand`의 생성·이름 변경·생명주기에 연결한 결과는 다음 절들에 구분한다. Brand의 기본 저장·조회는 아래 영속성 단계에 연결했다. 유스케이스·HTTP 연결과 API-16·18의 DB 상태 보존은 후속 [관리자 브랜드 TDD](commerce-tdd-admin-brand-http-log.md)에서 검증했다.

## 브랜드 생성·이름 변경의 TDD 계획

이름 규칙을 다시 구현하지 않고 `BrandName`을 소유하는 `Brand`에 연결한다. 기존 이름을 교체하기 전에 새 이름을 검증하여 실패 시 현재 상태를 보존한다. 관련 계약은 P02와 API-16·18의 이름 입력 및 실패 후 상태 보존이다. HTTP 응답과 DB 저장값은 도메인 단위 검증과 별도로 후속 [관리자 브랜드 TDD](commerce-tdd-admin-brand-http-log.md)에서 검증했다.

| 사례 ID | 입력·동작 | 도메인 단위 테스트 기대값 |
| --- | --- | --- |
| BRAND-01 | 앞뒤 공백이 있는 이름으로 생성 | 정리된 유효한 이름을 보관 |
| BRAND-02 | null·공백뿐인 이름·101자 이름으로 생성 | BrandNameException의 EMPTY_NAME 또는 NAME_TOO_LONG으로 거절 |
| BRAND-03 | 유효한 새 이름으로 변경 | 같은 브랜드 객체에 정리된 새 이름 반영 |
| BRAND-04 | null·공백뿐인 이름·101자 이름으로 변경 | 해당 이름 실패 사유를 유지하고 기존 이름 보존 |
| BRAND-05 | 코드 포인트 100자인 한글·이모지 이름으로 생성·변경 | 두 경로 모두 허용하고 같은 이름 검증 규칙 적용 |
| BRAND-06 | 잘못된 변경 후 유효한 이름으로 다시 변경 | 실패가 후속 변경을 막지 않으며 새 이름 반영 |

6개 규칙의 13개 실행 사례를 [BrandTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandTest.java)로 검증했다. 실제 Red·Green·Refactor 결과는 [브랜드 모델 실행 기록](commerce-tdd-brand-log.md)에 남긴다. 삭제 후 이름 변경 거절은 아래 생명주기 사례로 구분하며, A02·A04/API-16·18의 후속 HTTP 증거는 관리자 브랜드 기록에 구분한다.

## 브랜드 생명주기의 TDD 계획

P00·P04의 논리 삭제와 재삭제 상태 보존을 `Brand`의 메모리 상태에 연결했다. `deletedAt` 하나를 상태로 보관하고 `isDeleted()`는 이 값에서 도출한다. `delete(ZonedDateTime)`는 package-private이며 서버가 제공하는 시각을 받는다. HTTP에서 삭제 시각을 입력받는 계약을 추가한 것이 아니다. 삭제 후 이름 변경은 HTTP와 독립된 `BrandStateException.Reason.DELETED_BRAND`로 거절한다.

| 사례 ID | 입력·동작 | 도메인 단위 테스트 기대값 |
| --- | --- | --- |
| BRAND-LIFECYCLE-01 | 새 브랜드 생성 | 미삭제 상태, deletedAt은 null |
| BRAND-LIFECYCLE-02 | 서버 시각으로 첫 삭제 | 삭제 상태로 전환하고 전달한 시각 기록, 기존 이름 유지 |
| BRAND-LIFECYCLE-03 | 삭제 후 다른 서버 시각으로 재삭제 | 최초 deletedAt과 이름 유지, 추가 변경 없음 |
| BRAND-LIFECYCLE-04 | 삭제 후 유효한 새 이름 또는 같은 이름으로 변경 | 두 경우 모두 DELETED_BRAND로 거절하고 이름·삭제 시각 유지 |
| BRAND-LIFECYCLE-05 | 이름 변경 후 삭제 | 변경한 이름을 유지하고 삭제 시각 기록 |
| BRAND-LIFECYCLE-06 | 첫 삭제에 null 서버 시각 전달 | 내부 호출 오류인 NullPointerException으로 거절하고 미삭제 상태·이름 유지 |

6개 규칙의 7개 실행 사례를 [BrandLifecycleTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandLifecycleTest.java)로 단위 검증했다. 실제 과정과 검사 결과는 [브랜드 모델 실행 기록](commerce-tdd-brand-log.md)에 남긴다. 이 생명주기 단위 단계에서는 삭제된 브랜드와 잘못된 이름의 복합 오류를 다루지 않았다. HTTP 연결은 승인한 공통 처리 순서·오류 계약으로 검증한다. 미삭제 상품 조회 결과를 이용한 삭제 조건은 아래 서비스에서 검증하며, Brand ID·JPA·실제 저장·조회는 아래 영속성 단계에서 구현했다. BaseEntity는 상속하지 않으며, 후속 관리자 브랜드 단계에서 삭제 유스케이스의 DB 저장·행 잠금·HTTP를 연결했다.

## 브랜드 삭제 조건의 TDD 계획

`domain/brand/BrandDeletionService`는 `BrandRepository.findById(long)`로 삭제된 브랜드까지 포함해 대상을 조회한다. 이미 삭제됐다면 그대로 반환하고, 미삭제 상태라면 `domain/product/ProductRepository.existsNonDeletedByBrandId(long)`의 결과로 삭제 가능 여부를 판단한다. 외부에서 브랜드 객체와 ID를 따로 받지 않으며, 성공하면 해당 `Brand`의 삭제 상태를 변경해 반환한다. 이 반환이 DB 저장을 뜻하지는 않는다.

| 사례 ID | 조회 결과·동작 | 도메인 단위 테스트 기대값 |
| --- | --- | --- |
| BRAND-DELETE-01 | 대상 존재, 미삭제 상품 존재 결과 false | 해당 브랜드를 서버 시각으로 삭제하고 같은 객체 반환 |
| BRAND-DELETE-02 | 대상 존재, 미삭제 상품 존재 결과 true | NON_DELETED_PRODUCTS_EXIST로 거절하고 이름·미삭제 상태 유지 |
| BRAND-DELETE-03 | 대상 브랜드 없음 | BRAND_NOT_FOUND로 거절, 상품 조회 생략, 다른 브랜드 상태 유지 |
| BRAND-DELETE-04 | 이미 삭제된 대상 | 상품 조회 없이 기존 브랜드 반환, 최초 삭제 시각 유지 |
| BRAND-DELETE-05 | 상품 존재 조회 중 실패 | 실패를 전달하고 브랜드 이름·미삭제 상태 유지 |
| BRAND-DELETE-06 | 두 브랜드 ID에 서로 다른 상품 존재 결과 | 각 ID의 대상·조건만 적용하고 다른 브랜드와 혼동하지 않음 |
| BRAND-DELETE-07 | true 결과로 거절된 뒤 false 결과로 재요청 | 첫 실패 상태를 보존하고 조건이 바뀐 재요청에서 삭제 성공 |

대상 없음·미삭제 상품 존재의 실패 사유는 HTTP와 독립된 `BrandDeletionException`으로 구분한다. 7개 사례를 [BrandDeletionServiceTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandDeletionServiceTest.java)에서 조회 대역으로 검증했으며, 실제 과정과 결과는 [브랜드 모델 실행 기록](commerce-tdd-brand-log.md)에 남긴다. 재고 0인 상품 포함·삭제 상품 제외는 상품 조회 포트의 계약이다. boolean 대역으로는 이 조건의 실제 SQL이나 DB 행 잠금을 검증하지 못한다. 현재 `findById`도 잠금·저장을 보장하지 않는다. Brand의 ID·JPA 저장·조회 구현과 저장소 Spring 연결은 아래에서 검증했으며, 상품 조회 구현·삭제 유스케이스의 저장·잠금·Spring·HTTP 연결도 후속 [관리자 브랜드 TDD](commerce-tdd-admin-brand-http-log.md)에서 검증했다.

## 브랜드 저장·조회 매핑의 TDD

[BrandRepositoryIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/brand/BrandRepositoryIntegrationTest.java)는 실제 MySQL과 Spring으로 주입한 domain 저장소 계약을 사용한다. 테스트 전체를 하나의 트랜잭션으로 감싸지 않고 저장·조회 트랜잭션을 분리해 DB 왕복을 확인한다.

| 사례 ID | 검증할 행동 |
| --- | --- |
| BRAND-PERSIST-01 | 신규 저장의 양수 ID·감사 시각과 실제 이름 컬럼 |
| BRAND-PERSIST-02 | 커밋 후 다른 트랜잭션·다른 객체로 ID·이름·미삭제 상태 복원 |
| BRAND-PERSIST-03 | 없는 ID의 빈 결과 |
| BRAND-PERSIST-04 | 한글·이모지 각각 100개 이름 왕복, 2개 실행 사례 |
| BRAND-PERSIST-05 | SQL로 준비한 삭제 행을 삭제 시각까지 반환. 삭제 유스케이스 실행과 구분 |
| BRAND-PERSIST-06 | 저장 후 호출자 트랜잭션 실패 시 INSERT 롤백 |
| BRAND-PERSIST-07 | 변경 감지에 의한 이름 저장, createdAt 보존·updatedAt 갱신 |

이 7개 규칙은 8개 실행 사례로 검증한다. 도메인 이름 검증을 converter에 복제하지 않고 `BrandName`을 이용한다. 수정·삭제 유스케이스의 권한·업무 실패·경합·잠금과 운영 DDL은 별도 [관리자 브랜드](commerce-tdd-admin-brand-http-log.md)·[스키마 TDD](commerce-tdd-schema-log.md)에서 검증했다.

## 관리자 브랜드 신규 등록의 TDD

`BrandRegistrationService`는 전달된 `UserRole`을 `AdminAuthorization.requireAdmin`으로 확인한 뒤 Brand를 생성·저장한다. 초기 TDD의 fixture 기반 검사를 P15의 Spring Security 역할 경계에 맞춰 교체했다. 입력 이름 검증과 생성 상태는 기존 도메인에 맡기고 `AdminBrandInfo`로 내부 결과를 반환한다. 정상 등록 1개, 고객의 정상/잘못된 이름 2개, 관리자의 null·공백·101자 3개, 같은 이름의 별도 등록 1개를 실제 MySQL에서 검증했다. 총 7개 통합 사례와 Red·Green 과정은 [등록 기록](commerce-tdd-brand-registration-log.md)에 남긴다. A02의 HTTP 계약과 바인딩 전 관리자 게이트도 후속 [관리자 브랜드 TDD](commerce-tdd-admin-brand-http-log.md)에서 검증했다.

## 주문 수량 합산의 TDD

P01/P08의 확정 규칙을 `domain/order/OrderQuantities`로 구현했다. 금액·저장·HTTP와 독립된 상품별 수량의 불변 결과를 제공하고, 각 항목의 양수 ID·양수 수량과 동일 상품 합계의 Integer 범위를 검증한다. 별도 `OrderQuantityException`은 HTTP 상태 없이 사유만 구분한다.

| 사례 ID | 기대값 |
| --- | --- |
| ORDER-QUANTITY-01 | 서로 다른 상품의 수량을 각각 유지 |
| ORDER-QUANTITY-02 | 같은 상품의 비연속 입력도 2+3=5로 합산 |
| ORDER-QUANTITY-03 | 각 입력이 0·음수이면 합산 전 거절 |
| ORDER-QUANTITY-04 | 개별 값이 유효해도 합계 Integer 상한 초과 거절 |
| ORDER-QUANTITY-05 | null·빈 목록·null 항목 거절 |
| ORDER-QUANTITY-06 | 양수가 아닌 상품 ID 거절 |
| ORDER-QUANTITY-07 | Long 최댓값 ID·Integer 최댓값 수량·정확히 최댓값인 합계 허용 |
| ORDER-QUANTITY-08 | 입력 목록을 변경하지 않고 반환 수량의 외부 변경도 방지 |

[실행 기록](commerce-tdd-order-quantity-log.md)의 단위 테스트 16개가 통과했다. API-11의 실제 항목 1행 저장·재고 5 차감·HTTP 오류와 주문 전체 원자성은 별도 [주문 TDD](commerce-tdd-order-log.md)에서 검증했다. 값객체의 단위 검사와 API 완료 증거는 구분한다.

## 고객 브랜드 상세 조회의 TDD

`application/brand/BrandQueryService`는 고객에게 노출할 수 있는 브랜드만 조회한다. `@Service`로 등록하고 `@Transactional(readOnly = true)` 범위에서 domain 저장소를 호출한 뒤 내부 결과 `BrandInfo`를 만든다. 결과를 HTTP DTO로 바꾸는 책임은 interfaces에 남긴다.

| 사례 ID | 입력·상태 | 기대값 |
| --- | --- | --- |
| BRAND-QUERY-01 | 서로 다른 미삭제 브랜드 두 개 중 두 번째 ID 요청 | 요청한 ID·이름 반환, DB 상태 유지 |
| BRAND-QUERY-02 | 기존 브랜드가 있지만 요청 ID의 행은 없음 | BRAND_NOT_FOUND, 기존 행 유지 |
| BRAND-QUERY-03 | 삭제 시각이 저장된 브랜드 ID 요청 | 동일한 BRAND_NOT_FOUND, 모든 행·이름·감사 시각·삭제 시각 유지 |

[BrandQueryServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandQueryServiceIntegrationTest.java)는 실제 MySQL에 커밋한 데이터를 Spring 서비스 빈으로 조회한다. 테스트 전체를 하나의 트랜잭션으로 감싸지 않으며, 호출 전후 DB 스냅샷으로 상태 보존을 확인한다. `readOnly` 선언 자체가 쓰기를 차단한다고 가정하지 않는다. 삭제 상태는 SQL fixture로 준비하며 삭제 유스케이스의 완료를 뜻하지 않는다. 기존 저장소의 삭제 포함 계약과 관리자 조회 규칙은 유지한다. C01의 HTTP 연결·입력 오류·응답은 후속 [HTTP TDD](commerce-tdd-brand-http-log.md)에서 검증했다. API-01의 상품 상세는 별도 [상품 TDD](commerce-tdd-product-log.md)에서 검증했다.

## API·규칙별 테스트 기대값

아래는 **테스트 계획**이다. [API 계약표](commerce-api-contract.md)의 ID를 테스트 이름 또는 설명에 연결하고 HTTP 상태·응답 필드뿐 아니라 성공·실패 후 저장값을 함께 검증한다. 금액·포인트는 승인된 원 단위 정수 Long/BIGINT이며 아래 표현 상한은 `M=Long.MAX_VALUE=9,223,372,036,854,775,807`로 고정한다. 덧셈·곱셈의 범위 초과를 검출하고 실패 시 상태를 보존한다.

자원 ID와 수량의 범위는 이미 확정했다. 커머스 API의 `brandId`·`productId`·`orderId`에는 `Long.MAX_VALUE + 1`, 수량·재고에는 `Integer.MAX_VALUE + 1`을 원문 숫자로 보내 바인딩 단계의 범위 초과도 검증한다. 오류 응답은 승인된 400 INVALID_REQUEST로 검증하고 저장값은 유지한다. 현재 재고 객체의 `int` 단위 테스트만으로 HTTP 숫자 변환까지 검증했다고 보지 않는다.

커머스 HTTP 경계에서는 누락·null·허용하지 않은 필드와 JSON 타입 강제 변환을 거절한다. C01·C02·C03은 X-USER-ID를 무시하고, C06 경로와 관리자 주문 필터는 외부 식별 문자열을 사용한다. 관리자 권한 검사는 Spring Security로 경로·본문 바인딩 전에 실행한다. 관리자·일반 사용자·미식별 요청을 MockMvc로 검증하고 쓰기 권한 테스트에는 유효한 csrf()를 넣는다. CSRF 누락·불일치는 별도 403 사례로 검증한다. 405 METHOD_NOT_ALLOWED·415 UNSUPPORTED_MEDIA_TYPE·406 NOT_ACCEPTABLE의 승인된 정확한 메시지와 기존 Example API 관찰 계약 보존을 함께 검증한다.

### 상품 목록 검증용 fixture

브랜드 10·20은 미삭제이고, 상품 105만 삭제된 상태로 준비한다. 생성 시각은 같은 날짜의 아래 시각으로 고정한다. 좋아요 수는 값을 상품에 직접 넣지 않고 서로 다른 fixture 사용자들의 관계 행으로 만든다. 각 테스트는 초기 데이터를 독립적으로 준비한다.

| productId | brandId | 가격 | 재고 | 생성 시각 | 관계 수 | 상태 |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 10 | 1000 | 5 | 01:00:00Z | 2 | 미삭제 |
| 102 | 10 | 1000 | 3 | 02:00:00Z | 0 | 미삭제 |
| 103 | 20 | 2000 | 4 | 02:00:00Z | 2 | 미삭제 |
| 104 | 10 | 500 | 7 | 00:00:00Z | 2 | 미삭제 |
| 105 | 10 | 500 | 1 | 03:00:00Z | 1 | 삭제 |

| 사례 ID·관련 API | 정상·대표 오류 입력 | 응답 및 저장 기대값 |
| --- | --- | --- |
| API-01 / C01·C03 | 존재하는 미삭제 브랜드·상품 상세, 없는 ID, 삭제 ID | 정상 200·BrandView/ProductView. 상품 응답에 브랜드와 집계 likeCount 포함. 없음·삭제는 같은 404 코드·메시지. 조회로 저장 상태 변경 없음 |
| API-02 / C02·A06 | 위 fixture에서 latest / price_asc / likes_desc | 고객 순서 각각 `[103,102,101,104]`, `[104,102,101,103]`, `[104,103,101,102]`. 동률은 ID 내림차순. 관리자 latest는 삭제 105도 포함하여 `[105,103,102,101,104]`(삭제 포함·동률 기준은 P05·06 확정) |
| API-03 / C02 | `brandId=10&sort=price_asc&size=2`로 page=0/1/2를 각각 요청 | 페이지는 `[104,102]`, `[101]`, `[]`; totalElements=3, totalPages=2. 브랜드 필터와 정렬을 모두 적용한 뒤 페이지를 자름. 별도 없는/삭제 브랜드 필터는 200 빈 페이지 |
| API-04 / 모든 목록 API | sort=unknown, page=-1, size=0·101, page=abc | 지원하는 해당 쿼리의 잘못된 입력은 `400 INVALID_REQUEST`. 기본값으로 대체하거나 저장값을 변경하지 않음 |
| API-05 / C04·C05·C06 | 관계 없는 alice가 관계 수 2인 상품에 등록→중복 등록→취소→재취소→재등록 | 모든 성공 상태·응답 필드는 P04·P12 확정이며 liked는 true→true→false→false→true, likeCount는 3→3→2→2→3. 본인 DB 관계 행 수는 1→1→0→0→1로 물리 삭제와 재등록을 검증. 본인 목록에도 반영하고 다른 사용자의 관계는 유지 |
| API-06 / C04·C05·C06 | 본인 관계가 있는 상품 삭제 후 새 등록·내 목록·취소 | 상품 논리 삭제만으로 관계를 제거하지 않음. 새 등록 404, 내 목록에서 제외, 본인 취소 200·관계 행 실제 삭제·집계 1 감소. 다른 사용자의 관계는 유지 |
| API-07 / C07·C08 | 잔액 2000에 amount=3000, 같은 요청을 다시 충전, 별도 잔액 0 조회 | 첫 충전 후 DB·GET balance=5000. 같은 요청의 두 번째 성공도 새 충전이므로 DB·GET balance=8000(P07). 충전 성공 상태는 200. 별도 잔액 0 fixture는 200·balance=0 |
| API-08 / C07 | amount 누락·null·문자열·소수·0·음수·표현 상한 M 초과 | 400·INVALID_REQUEST, 초기 잔액 2000 유지. 잔액 1에 유효한 최댓값 M을 충전하면 합산 초과 409·POINT_BALANCE_LIMIT_EXCEEDED, 잔액 1 유지 |
| API-09 / C09 | 상품101×2, 상품103×1, 잔액10000 | 201·DRAFT, 소계2000·2000, totalAmount=4000, paidAmount/confirmedAt 생략. 주문1행·항목2행 저장. 재고5·4와 잔액10000 유지. 별도 반복 요청 테스트에서는 같은 요청을 두 번 성공시키면 서로 다른 주문2행·항목4행이며 차감 없음(P07) |
| API-10 / C09 | 빈 items, 수량0·음수·소수·누락·Integer 상한 초과, 없는/삭제 상품 또는 삭제 브랜드의 상품 | 입력 오류400 또는 PRODUCT_NOT_FOUND404. 주문·항목 저장 없고 잔액·재고 유지. 뒤 품목이 실패해도 앞 품목만 저장하지 않음 |
| API-11 / C09·C10 | 같은 상품에 수량2·3을 전달. 별도 상한 테스트는 Integer.MAX_VALUE와 1을 전달 | P01 합산 확정: 항목1행·수량5·소계=단가×5. 생성201은 확정 계약. 합산된 주문을 현재 재고4로 확정하면409, 재고5면 다른 조건 충족 시5차감. 개별 수량이 유효해도 합이 Integer 범위를 초과하면 거절·주문과 항목 저장 없음. 초과 오류는400 INVALID_REQUEST |
| API-12 / C10 | API-09의 본인 DRAFT 확정 | 200·CONFIRMED·paidAmount=4000·confirmedAt 저장. 재고3·3, 잔액6000. 주문·모든 재고·잔액이 한 트랜잭션으로 반영 |
| API-13 / C10 | 재고 부족 / 잔액3999 / DRAFT 생성 후 상품 삭제 / 저장 수량을0으로 훼손한 오류 fixture | 각 409 INSUFFICIENT_STOCK / 409 INSUFFICIENT_POINTS / 404 PRODUCT_NOT_FOUND / 500 INTERNAL_ERROR. DRAFT와 요청 직전의 모든 재고·잔액 유지. 부족 조건 개선 후 별도 확정 요청 가능하며 삭제는 재고 증가만으로 해결되지 않음 |
| API-14 / C10·C12 | 생성 후 상품명·가격 변경, 확정 후 재확정·상품 삭제 | 생성 당시 이름·단가·총액 유지. 확정 재요청200은 기존 paidAmount·confirmedAt을 그대로 반환하고 추가 차감 없음. 상품 삭제 후에도 과거 주문 조회200 |
| API-15 / C06·C10·C11·C12 | alice 헤더로 bob 목록·주문 접근, 없는 주문 접근 | 타인 좋아요 목록404 USER_NOT_FOUND. 타인/없는 주문은 동일404 ORDER_NOT_FOUND·메시지. 내 주문 목록에는 alice 주문만 포함하고 타인 정보·변경 없음 |
| API-16 / A01~A05·C01 | 관리자 브랜드 생성→목록·상세→이름 수정→고객 상세→삭제·재삭제 | 생성201, 조회·수정200, 고객 상세에 수정된 이름. 이름 공백·101자 입력400·기존 값 유지. 미삭제 상품이 하나라도 연결되면 재고0이어도 삭제409·브랜드 유지. 상품이 없거나 모두 삭제되면 삭제200·고객 상세404. 브랜드 행·ID를 보존하고 deleted_at 기록. 재삭제는200·최초 deleted_at과 관련 상품 상태 유지(P00·P04) |
| API-17 / A06~A10·C02·C03 | 유효한 상품 생성→조회→상품명·가격 수정→삭제·재삭제 | 생성201, 조회·수정200, 고객 조회에 현재 값 반영. brandId·재고는 유지. brandId를 PUT에 전달하면400 INVALID_REQUEST(P05·P12). 없는/삭제 브랜드로 생성404·저장 없음. 삭제200 후 고객 목록 제외·상세404·기존 주문 보존. 상품 행·ID를 보존하고 deleted_at 기록. 재삭제는200·최초 deleted_at과 주문·좋아요 관계 유지(P00·P04) |
| API-18 / A04·A09·A11 | 삭제된 브랜드·상품 수정 또는 삭제 상품 재고 변경 | 각404 BRAND_NOT_FOUND/PRODUCT_NOT_FOUND, 기존 정보·재고·삭제 상태 유지. name 누락·공백·101자, price 음수·타입·범위 오류는400. 가격0은 P02에 따라 거절 |
| API-19 / A11·C03 | 현재 재고10에서 stockQuantity=3, 이어서0, 별도 -1 요청. 별도 상한 테스트는 Integer.MAX_VALUE와 그 초과값 | 200·최종3, 200·최종0, 음수는400·직전 재고 유지. 최댓값은 정상 설정하고 초과 입력은 거절·직전 재고 유지. 고객 상세에도 최종 수량 반영. 더하기 연산으로 처리하지 않음 |
| API-20 / A12·A13·C11·C12 | alice·bob의 DRAFT/CONFIRMED 주문, 관리자 구매자·상태 필터 | 관리자 목록·상세200·구매자/품목/상태/총액/결제액 포함. 필터와 일치한 주문만 반환. 고객 응답은 본인 주문이며 관리자 필드 미노출. 없는 주문404 |
| API-21 / 본인·관리자 API 공통 | 고객 X-USER-ID 누락·공백 / 매핑 없음, 관리자의 Security 역할·CSRF | 고객은 400 INVALID_REQUEST / 404 USER_NOT_FOUND와 단일 fixture를 유지한다. 관리자는 P15에 따라 일반·미식별 요청 모두403 FORBIDDEN, ADMIN 허용. 모든 쓰기 권한 테스트에 유효한 csrf()를 넣고 CSRF 누락·불일치403은 별도 검증한다. X-USER-ID만으로 관리자 접근 불가. 일반 사용자의 잘못된 ID·깨진 JSON에도 권한 오류 우선이며 전체 DB 상태 보존 |
| API-22 / C09·C10 | 표현 가능한 단가·수량의 곱 또는 소계 합이 금액 표현 상한 M을 초과 | C09는409 ORDER_AMOUNT_LIMIT_EXCEEDED·주문 저장 없음. C10은 유효하게 저장한 생성 금액으로 처리하고 현재 가격으로 재산출하지 않음 |
| API-23 / C10·C07·A11 | 같은 주문 동시 확정, 서로 다른 주문의 재고·잔액 경쟁, 충전·재고 설정과 확정 경쟁 | 같은 주문은 한 번만 차감하고 성공 결과 공유. 다른 조건이 충족되고 재고5에 각4개인 두 주문은 하나만 확정, 재고1·실패 주문DRAFT. 잔액·재고 음수와 갱신 유실·부분 저장 없음. READ_COMMITTED·주문→사용자→브랜드 ID 오름차순→상품 ID 오름차순 잠금에서 경합 결과를 검증. 별도 장기 점유·교착 사례는 3초 잠금 타임아웃/409 CONCURRENT_MODIFICATION·서버 자동 재시도 없음·실패 요청의 추가 효과 없음을 검증 |
| API-24 / A05·A07·A10·C10 | 브랜드 삭제와 상품 등록, 상품 삭제와 주문 확정 경쟁 | 행 잠금으로 실행 순서를 보장하여 삭제 브랜드에 미삭제 상품이 생기지 않음. 삭제가 먼저 반영된 상품의 DRAFT 확정은 거절. 확정이 먼저 완료됐다면 이후 삭제해도 확정 결과 보존 |

대표 사례만 통과시키고 API 계약표의 다른 행을 생략하지 않는다. 각 API에는 정상 1개 이상·대표 오류 1개 이상의 HTTP 계약 검증을 연결한다. 삭제·수정·충전·확정의 실패는 오류 코드뿐 아니라 **실패한 요청의 변경이 남지 않는지**도 확인한다. 단독 실행에서는 변경 전후 DB 값이 같아야 한다. 동시 실행에서는 다른 요청의 성공 효과를 보존하고 실패 요청의 추가 효과가 없는지 확인한다. API-23의 재고5에서 두 주문이 각4개를 요청하는 경우 최종 재고1·성공1건·실패 주문DRAFT를 유지해야 한다.

## 충전부터 주문 조회까지의 연결 검증

잔액 SQL 갱신 없이 fixture 초기 잔액 0에서 시작한다. 충전 API로 10000원을 충전하고 단가 2000원×2개·3000원×1개를 DRAFT로 생성한다. 생성 직후 잔액10000·재고 유지, 확정 후 결제액7000·잔액3000·각 상품 재고 차감을 확인한다. 별도 HTTP 요청으로 내 주문 목록·상세와 잔액을 다시 조회해 저장 결과를 검증하며 다른 사용자의 주문·잔액도 보존한다. [PointOrderJourneyApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/PointOrderJourneyApiE2ETest.java)가 이 흐름을 담당한다.

## 작은 기능의 구현 순서와 검증 기록

아래는 순서 제안이며 한 행의 API를 한 번에 모두 구현한다는 뜻이 아니다. 매번 변경할 책임·파일·관련 테스트를 정리하고 작은 기능 하나의 diff와 결과를 확인한 뒤 다음으로 넘어간다.

문서를 보완한 뒤에는 다음 순서로 기존 구현과 다음 규칙을 연결한다.

1. **문서 보완:** 업무 계약과 [계층·DIP 기준](commerce-erd-draft.md#구조와-코드-의존)을 확인한다. 새로운 업무 정책이나 검사 기준 변경은 먼저 질문하고, 설명 보완과 구분한다.
2. **기존 구현 재검증:** 영향을 받는 책임·입출력 타입·의존을 대조하고 관련 테스트를 확인한다. 설명만 보완했고 현재 구현이 이미 맞으면 유지한다. 이 검증을 새로운 Red로 기록하지 않는다.
3. **다음 규칙의 TDD:** 다음 작은 행동의 기대값을 테스트에 추가해 의도한 실패를 확인하고, 최소 구현 후 통과시키고 리팩터링한다. 이미 통과하는 경계 사례를 인위적으로 실패시키지 않는다.
4. **기록 갱신:** 실제 구현·검사 결과와 남은 범위를 문서에 반영한 뒤 다음 규칙으로 진행한다.

| 순서 | 기능 단위 | 연결할 계약·검증 |
| --- | --- | --- |
| 1 | 상품 재고 차감·최종 수량 설정 규칙 | STOCK-01~05와 STOCK-SET-01~05의 Red → Green → Refactor |
| 2 | 사용자 식별·권한 해석, fixture 연결·HTTP 진입 검증 | IDENTITY-01~10의 TDD 완료. 후속 HTTP에서 X-USER-ID와 API-21 연결까지 검증 |
| 3 | 브랜드 이름·모델·생명주기·삭제 조건 후 등록·상세부터 CRUD 하나씩 | BRAND-NAME-01~04·BRAND-01~06·BRAND-LIFECYCLE-01~06·BRAND-DELETE-01~07 단위 검증과 BRAND-PERSIST-01~07의 Brand DB 매핑 검증 완료. BRAND-QUERY-01~03의 고객 상세 application 검증 완료. C01 HTTP와 관리자 등록·상세 application도 검증 완료. 상품 존재 SQL·나머지 관리자 유스케이스·행 잠금·A01~A05 HTTP와 API-16·18도 검증 완료. 상품 등록과 브랜드 삭제의 브랜드 잠금 공유·경합은 [관리자 브랜드 기록](commerce-tdd-admin-brand-http-log.md) 참조 |
| 4 | 상품 등록·수정·재고 설정 각각 | A07~A11, API-17~19, API-24의 브랜드 삭제·상품 등록 경합. 수량 객체를 상품 생명주기에 연결하고 STOCK-06 검증 |
| 5 | 상품 상세·목록·필터·페이지·정렬을 순서대로 | C02·C03, A06, API-01~04 |
| 6 | 좋아요 등록·취소·내 목록 각각 | C04~C06, API-05·06·15. 동시 중복도 관계1건·추가 변경 없음 확인. 신규·중복·취소의 200과 LikeResult는 확정 계약 |
| 7 | 포인트 충전·저장 잔액 조회 | C07·C08, API-07·08 |
| 8 | 주문 생성·목록·상세 | C09·C11·C12, API-09~11·15·22 |
| 9 | 주문 확정·재요청·동시성 | C10, API-12~14·23·24 |
| 10 | 관리자 주문 조회·고객 조회와 차이 | A12·A13, API-20·21 |

도메인 단위 테스트는 상태·계산을, API 테스트는 method/path/입력/응답/오류를, DB 통합 테스트는 저장·유일 제약·트랜잭션·동시성을 검증한다. 단계별 작업 기록에는 **관련 계약 ID, Red의 의도한 실패, Green 결과, Refactor 내용, diff, 실행한 검사와 미실행 이유**를 남긴다. 아직 수행하지 않은 단계의 성공 기록은 만들지 않는다.

기능별 테스트에 이어 관련 Checkstyle·ArchUnit을 확인한다. 아래 명령은 검사 방법이며, 문서 작성으로 실행 완료한 결과가 아니다.

```shell
./gradlew :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest
./gradlew :apps:commerce-api:test --tests 'com.loopers.architecture.ArchitectureTest'
```
