# 커머스 전체 완료 체크리스트

[전체 설계](commerce-erd-draft.md) · [API 계약](commerce-api-contract.md) · [정책 선택](commerce-policy-decisions.md) · [TDD 계획](commerce-tdd-plan.md)

목표는 고객 12개·관리자 13개 API와 관련 도메인·저장·권한·동시성·운영 DDL을 끝까지 연결하고 검증하는 것이다. 이 문서는 완료 증거를 추적하며 새 업무 정책을 정하지 않는다. 계약의 상세 기대값은 연결한 원문을 따른다.

## 기준선과 기록 방법

이번 증분 전 기준선은 [고객 브랜드 조회 application 기록](commerce-tdd-brand-query-log.md#검사-결과)의 **111개 테스트 통과**, Checkstyle main/test 위반 0개, ArchUnit 통과와 HTTP API **0/25개**다. 재고 수량, 사용자 식별·fixture, 브랜드 도메인·영속성·고객 상세 application까지의 과거 증거로 보존한다. 현재 결과는 아래 최신 통합 검사에 구분한다. 테스트 수는 전체 완성률을 뜻하지 않는다.

- `pending`: 구현 중이거나, 일부 검증만 있거나, 완료 증거가 없는 항목. 기존 도메인 테스트만으로 API를 완료 처리하지 않는다.
- `done`: 합의한 기대값의 구현·검증이 끝나고 증거 칸에 테스트 파일·사례와 실행 기록 링크를 남긴 항목.
- API별 정상·대표 오류·권한·저장 상태를 해당 범위에 맞게 검증한다. 복수 API가 연결된 TDD 행은 모든 연결을 검증한 뒤 완료한다.
- 실행 기록에는 변경 범위, 실제 Red·Green·Refactor, 실행 명령·결과·시점과 남은 범위를 남긴다. 이미 통과한 사례는 인위적인 Red로 만들지 않는다. 과거 로그의 테스트 수는 보존하고 최신 전체 검사 결과를 별도로 추가한다.

## 최신 통합 검사

이전 448개 검사는 관리자 접근 지원 설정·연결 시나리오·다른 모듈 정적 검사 요구를 빠뜨렸다. 과제 피드백의 세 항목을 보완하고 아래 결과로 완료를 다시 확인했다. 이전 완료 판단을 그대로 재사용하지 않는다.

2026-09-18 14:22 KST, 마지막 CSRF 오류 본문 검증까지 포함한 최종 코드에서 아래 명령이 종료 코드 0으로 성공했다.

```shell
./gradlew :apps:commerce-api:check --console=plain -q
```

| 검사 | 실제 결과 |
| --- | --- |
| 전체 테스트 XML 합산 | **51개 테스트 스위트·506개 통과**, 실패·오류·건너뜀 0개 |
| 관리자 접근 보완 | 실제 controller·application·JPA·DB를 사용한 MockMvc 57개. ADMIN 허용, 일반·미식별403, 유효한 CSRF로 권한 거절, CSRF 누락/불일치403, 헤더 위조 거절 |
| 충전·주문 연결 보완 | 초기 잔액0→충전 API10000→복수 품목 주문·확정7000→내 주문 목록·상세·잔액3000 조회 1개 |
| 실제 HTTP API 완료 | **25/25개: 고객 C01~C12·관리자 A01~A13** |
| 요구사항 사례 | **API-01~24 완료**, 각 행에 기능별 테스트·실행 기록 연결 |
| 운영 스키마 | 6개 수동 SQL의 실제 MySQL 적용·JPA validate·저장·제약 검증. 기존 브랜드 11개와 전체 스키마 31개, 합계 42개 통과 |
| 동시성·롤백 | 브랜드 경합 3개·주문 경합 7개, 좋아요 중복·잔액 경쟁과 실제 SQL 반영 후 롤백 검증 포함 |
| 기존 회귀 | 기존 도메인·C01·Example·고객 fixture 계약 유지. 관리자 요청자 입력과 400/404는 사용자가 요청한 과제 지원 방식·403으로 교정하고 업무·DB 기대값은 유지 |
| Checkstyle | 9개 Java 모듈의 main/test 및 jpa·redis·kafka testFixtures까지 21개 태스크 연결. 소스가 있는 11개 XML 보고서의 위반 합계0, 소스 없는 태스크는 NO-SOURCE |
| ArchUnit | ArchitectureTest 1개 통과. Layer-first 계층 의존 규칙 유지 |
| 문서·변경 검토 | AGENTS와 week2 문서의 로컬 링크·앵커·코드 블록 검사 오류 0개, git diff --check 통과 |

테스트 수는 `apps/commerce-api/build/test-results/test/TEST-*.xml`의 합산이며 개별 증분 실행 수를 더하지 않았다. Checkstyle은 각 모듈의 `build/reports/checkstyle/*.xml`을 모두 확인했다. API check는 다른 모듈의 정적 검사를 포함하며 그 모듈의 별도 통합 테스트까지 실행했다는 뜻은 아니다. 과거 로그의 111·167·448개 결과는 당시 기준선으로 보존한다.

이번 보완의 실제 Red·Green과 변경 이유는 [관리자 Security](commerce-admin-security-log.md)·[전체 모듈 Checkstyle](commerce-checkstyle-scope-log.md)·[충전부터 주문 조회](commerce-tdd-order-log.md#과제-피드백-충전부터-주문-조회까지)에 기록했다.

선행 기능의 실제 Red·Green·Refactor는 [브랜드 HTTP](commerce-tdd-admin-brand-http-log.md), [상품](commerce-tdd-product-log.md), [포인트·좋아요](commerce-tdd-point-like-log.md), [주문](commerce-tdd-order-log.md), [전체 스키마](commerce-tdd-schema-log.md) 기록에 구분한다. 기존 구현에서 바로 통과한 추가 경계 사례는 요구사항 검증으로 기록했다. 운영 SQL 적용 방법과 로컬 자동 DDL 설정의 차이는 [스키마 적용 안내](commerce-schema-operations.md)를 따른다.

## 정책 확인

P00~P15의 확정 내용을 따른다. P15는 사용자의 과제 피드백 수정 요청으로 관리자 접근 방식과 검증 범위를 바로잡은 계약이다. 2026-09-18 추천 묶음에 대한 사용자의 “어 진행해줘” 답변으로 아래 정책 확인을 마쳤다. 이 표의 `confirmed`는 정책·문서 반영 상태이며, API·DB·동시성 구현의 `done`과 다르다. 새로운 완료 증거가 없는 구현 항목은 pending을 유지한다.

| 항목 | 확정한 범위 | 정책 상태 | 결정·문서 반영 증거 |
| --- | --- | --- | --- |
| 금액·포인트 표현 | 원 단위 정수 Long/BIGINT, Long.MAX_VALUE 상한, 덧셈·곱셈 범위 초과 검출 | confirmed | [P08과 비용 비교](commerce-policy-decisions.md#금액-자료형의-공식-자료-확인과-비용-비교) |
| 나머지 API 계약 | 전체 응답·상태·오류·필터·정렬, 엄격 JSON, C02·C03 공개 조회와 헤더 무시, C06/관리자 필터의 외부 userId, 405·415·406 | confirmed | [P12](commerce-policy-decisions.md#구현-전에-확인할-정책-선택) · [API 계약](commerce-api-contract.md) |
| 구체 잠금 설계 | READ_COMMITTED, 주문→사용자→브랜드 ID 오름차순→상품 ID 오름차순. 상품 등록·변경/브랜드 삭제의 브랜드 잠금 공유, 좋아요의 상품 잠금. 3초·자동 재시도 없음·교착/타임아웃409 | confirmed | [P13](commerce-policy-decisions.md#구현-전에-확인할-정책-선택) |
| 상품명 길이 | 브랜드명·상품명 모두 앞뒤 공백 제거 후 Unicode 코드 포인트 1~100자 | confirmed | [P02](commerce-policy-decisions.md#구현-전에-확인할-정책-선택) |
| 사용자 초기 데이터 | alice→1/CUSTOMER, bob→2/CUSTOMER, admin→3/ADMIN, 초기 잔액 0 | confirmed | [P03](commerce-policy-decisions.md#구현-전에-확인할-정책-선택). 실제 DB 초기화는 아래 구현 항목으로 별도 검증 |
| 오류 처리 순서 | 관리자 권한 우선과 승인된 공통 처리 순서·오류 매핑. 기존 Example 계약 유지 | confirmed | [공통 계약](commerce-api-contract.md#공통-입력식별응답) · [오류 계약](commerce-api-contract.md#대표-오류와-상태-보존-제안) |
| 구현 순서 | JPA 유지, 기능 구현 후 나머지 운영 수동 DDL 작성·검증 | confirmed | [P14](commerce-policy-decisions.md#구현-전에-확인할-정책-선택). 기존 브랜드 SQL 보존 |
| 과제 관리자 접근·검증 보완 | 고객 fixture와 관리자 Security 역할 분리, 미식별403·쓰기 CSRF, 연결 시나리오와 모든 Java 모듈 lint | confirmed | [P15](commerce-policy-decisions.md#구현-전에-확인할-정책-선택) · [Security 기록](commerce-admin-security-log.md) · [Checkstyle 기록](commerce-checkstyle-scope-log.md) |

## API 25개 완료 증거

경로는 [고객·관리자 계약표](commerce-api-contract.md#고객-api-계약표)를 따른다. 각 행의 `done`은 HTTP부터 필요한 저장 처리까지의 검증을 뜻한다. C01의 기존 [application 검증](commerce-tdd-brand-query-log.md)은 선행 증거이며 HTTP 완료 증거는 아니다. 모든 A01~A13에는 기존 업무 검증과 함께 [AdminSecurityApiTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/commerce/AdminSecurityApiTest.java)의 실제 관리자·일반·미식별 요청 검증을 적용했다.

| ID | Method / Path | 연결 TDD | 상태 | 완료 증거: 테스트·실행 기록 |
| --- | --- | --- | --- | --- |
| C01 | `GET /api/v1/brands/{brandId}` | API-01·16 | done | [BrandV1ApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/BrandV1ApiE2ETest.java) BRAND-HTTP-01~06: 15개 사례, [실제 Red·Green·Refactor](commerce-tdd-brand-http-log.md) |
| C02 | `GET /api/v1/products` | API-02~04·17 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| C03 | `GET /api/v1/products/{productId}` | API-01·17·19 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| C04 | `POST /api/v1/products/{productId}/likes` | API-05·06·21 | done | [좋아요 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/like/LikeApiE2ETest.java) · [실행 기록](commerce-tdd-point-like-log.md) |
| C05 | `DELETE /api/v1/products/{productId}/likes` | API-05·06·21 | done | [좋아요 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/like/LikeApiE2ETest.java) · [실행 기록](commerce-tdd-point-like-log.md) |
| C06 | `GET /api/v1/users/{userId}/likes` | API-04~06·15·21 | done | [좋아요 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/like/LikeApiE2ETest.java) · [실행 기록](commerce-tdd-point-like-log.md) |
| C07 | `POST /api/v1/points/charge` | API-07·08·21·23 | done | [포인트 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/point/PointApiE2ETest.java) · [실행 기록](commerce-tdd-point-like-log.md) |
| C08 | `GET /api/v1/points` | API-07·21 | done | [포인트 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/point/PointApiE2ETest.java) · [실행 기록](commerce-tdd-point-like-log.md) |
| C09 | `POST /api/v1/orders` | API-09~11·21·22 | done | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [실행 기록](commerce-tdd-order-log.md) |
| C10 | `POST /api/v1/orders/{orderId}/confirm` | API-11~15·21·23·24 | done | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [실행 기록](commerce-tdd-order-log.md) |
| C11 | `GET /api/v1/orders` | API-04·15·20·21 | done | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [실행 기록](commerce-tdd-order-log.md) |
| C12 | `GET /api/v1/orders/{orderId}` | API-14·15·20·21 | done | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [실행 기록](commerce-tdd-order-log.md) |
| A01 | `GET /api-admin/v1/brands` | API-04·16·21 | done | [관리자 브랜드 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/AdminBrandMutationApiE2ETest.java) · [실행 기록](commerce-tdd-admin-brand-http-log.md) |
| A02 | `POST /api-admin/v1/brands` | API-16·21 | done | [관리자 브랜드 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/AdminBrandMutationApiE2ETest.java) · [실행 기록](commerce-tdd-admin-brand-http-log.md) |
| A03 | `GET /api-admin/v1/brands/{brandId}` | API-16·21 | done | [관리자 브랜드 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/AdminBrandMutationApiE2ETest.java) · [실행 기록](commerce-tdd-admin-brand-http-log.md) |
| A04 | `PUT /api-admin/v1/brands/{brandId}` | API-16·18·21 | done | [관리자 브랜드 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/AdminBrandMutationApiE2ETest.java) · [실행 기록](commerce-tdd-admin-brand-http-log.md) |
| A05 | `DELETE /api-admin/v1/brands/{brandId}` | API-16·21·24 | done | [관리자 브랜드 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/AdminBrandMutationApiE2ETest.java) · [실행 기록](commerce-tdd-admin-brand-http-log.md) |
| A06 | `GET /api-admin/v1/products` | API-02·04·17·21 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A07 | `POST /api-admin/v1/products` | API-17·21·24 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A08 | `GET /api-admin/v1/products/{productId}` | API-17·21 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A09 | `PUT /api-admin/v1/products/{productId}` | API-17·18·21 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A10 | `DELETE /api-admin/v1/products/{productId}` | API-17·21·24 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A11 | `PUT /api-admin/v1/products/{productId}/stock` | API-18·19·21·23 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A12 | `GET /api-admin/v1/orders` | API-04·20·21 | done | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [실행 기록](commerce-tdd-order-log.md) |
| A13 | `GET /api-admin/v1/orders/{orderId}` | API-20·21 | done | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [실행 기록](commerce-tdd-order-log.md) |

## TDD API-01~24 완료 증거

이 표는 [TDD 기대값](commerce-tdd-plan.md#api규칙별-테스트-기대값)의 색인이다. fixture 값·정렬 순서·응답·DB 기대값을 이 요약으로 대체하지 않는다. 후속 승인한 금액·HTTP·잠금 계약의 기대값으로 실행하며 완료 여부는 실제 증거로 갱신한다.

| 사례 | 검증 범위 | 상태 | 완료 증거: 테스트·실행 기록 |
| --- | --- | --- | --- |
| API-01 | 브랜드·상품 상세 정상/없음/삭제, 조회 상태 보존 | done | [상품 기록](commerce-tdd-product-log.md) · [C01 기록](commerce-tdd-brand-http-log.md) |
| API-02 | 상품 세 정렬·동률 순서·관리자 삭제 행 포함 | done | [상품 기록](commerce-tdd-product-log.md) |
| API-03 | 브랜드 필터·정렬 후 페이지·전체 건수·빈 결과 | done | [상품 기록](commerce-tdd-product-log.md) |
| API-04 | 모든 목록의 잘못된 필터·정렬·페이지 입력 | done | 목록별 형식 검증: [브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md) · [상품 기록](commerce-tdd-product-log.md) · [포인트·좋아요 기록](commerce-tdd-point-like-log.md) · [주문 기록](commerce-tdd-order-log.md) |
| API-05 | 좋아요 등록·중복·취소·재취소·재등록과 실제 관계 수 | done | [포인트·좋아요 기록](commerce-tdd-point-like-log.md) |
| API-06 | 상품 삭제 후 좋아요 조회 제외·신규 거절·본인 관계 물리 삭제 | done | [포인트·좋아요 기록](commerce-tdd-point-like-log.md) |
| API-07 | 충전·반복 성공의 신규 반영·잔액 조회 | done | [포인트·좋아요 기록](commerce-tdd-point-like-log.md) |
| API-08 | 충전 입력 오류·합산 범위 초과·잔액 보존 | done | [포인트·좋아요 기록](commerce-tdd-point-like-log.md) |
| API-09 | DRAFT·항목·금액 저장, 차감 없음, 반복 생성은 새 주문 | done | [주문 기록](commerce-tdd-order-log.md) |
| API-10 | 주문 생성 입력·대상 오류와 부분 저장 방지 | done | [주문 기록](commerce-tdd-order-log.md) · OrderScenarioApiE2ETest의 없는/삭제 상품·삭제 브랜드 3개 경우 |
| API-11 | 중복 상품 수량 합산·상한·합산 수량으로 확정 | done | [주문 기록](commerce-tdd-order-log.md) · OrderScenarioApiE2ETest의 합산 수량 5·재고 4 실패 후 5 성공 |
| API-12 | 확정 시 주문·모든 재고·잔액의 원자적 반영 | done | [주문 기록](commerce-tdd-order-log.md) · OrderScenarioApiE2ETest의 4000원/재고 3·3/잔액 6000 |
| API-13 | 재고/잔액 부족·삭제·잘못된 저장 수량과 실패 상태 보존 | done | [주문 기록](commerce-tdd-order-log.md) |
| API-14 | 생성 스냅샷·재확정의 기존 결과·삭제 후 주문 보존 | done | [주문 기록](commerce-tdd-order-log.md) |
| API-15 | 본인 좋아요·주문 접근, 타인/없는 대상 구분과 정보 비노출 | done | [포인트·좋아요 기록](commerce-tdd-point-like-log.md) · [주문 기록](commerce-tdd-order-log.md) |
| API-16 | 브랜드 CRUD·고객 반영·미삭제 상품 삭제 방지·재삭제 보존 | done | [브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md) |
| API-17 | 상품 CRUD·브랜드 유지·고객 반영·논리 삭제·관계 보존 | done | [상품 기록](commerce-tdd-product-log.md) · [포인트·좋아요 기록](commerce-tdd-point-like-log.md) · [주문 기록](commerce-tdd-order-log.md) |
| API-18 | 삭제 대상 변경 거절·이름/가격 입력 오류와 상태 보존 | done | [브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md) · [상품 기록](commerce-tdd-product-log.md) |
| API-19 | 최종 재고 설정·0·음수·Integer 경계·고객 조회 반영 | done | [상품 기록](commerce-tdd-product-log.md) |
| API-20 | 관리자 주문 상세·필터와 고객 조회의 범위 차이 | done | [주문 기록](commerce-tdd-order-log.md) |
| API-21 | 고객 fixture·관리자 Security 권한 우선·CSRF·상태 보존 | done | [Security 보완](commerce-admin-security-log.md) · [브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md) · [포인트·좋아요 기록](commerce-tdd-point-like-log.md) · [주문 기록](commerce-tdd-order-log.md) |
| API-22 | 단가×수량·소계 합의 금액 범위 초과와 저장 방지 | done | [주문 기록](commerce-tdd-order-log.md) |
| API-23 | 같은 주문·재고·잔액 경쟁, 충전/재고 설정과 확정 경합 | done | [주문 기록](commerce-tdd-order-log.md) · OrderConcurrencyIntegrationTest |
| API-24 | 브랜드 삭제/상품 등록, 상품 삭제/주문 확정 경합 | done | [브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md) · [주문 기록](commerce-tdd-order-log.md) |

## 통합·운영·최종 회귀 완료 증거

아래 모든 항목을 최종 전체 검사에 포함했다. 각 기능의 과거 단계별 기록과 현재 완료 상태를 구분한다.

| 항목 | 완료 확인 범위 | 상태 | 완료 증거: 파일·실행 기록 |
| --- | --- | --- | --- |
| 운영 DDL·스키마 | 6개 테이블의 수동 DDL, FK·유일 제약·논리 삭제·인덱스·자료형·예약어 인용. 빈 MySQL에 실제 적용 | done | [전체 스키마 42개 검증](commerce-tdd-schema-log.md) · [적용 순서·재실행 안내](commerce-schema-operations.md) |
| 초기 fixture | 단일 외부 ID·역할 매핑과 DB ID 1/2/3 연결, 초기 잔액 0, 재실행 시 기존 잔액·감사 시각 보존 | done | [FixtureUserInitializerIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/user/FixtureUserInitializerIntegrationTest.java) · [포인트 기록](commerce-tdd-point-like-log.md) |
| 실제 조회 계약 | 재고 0 포함·삭제 제외·브랜드 구분, 실제 관계 집계·정렬·필터 후 페이지·전체 건수 | done | [ProductQueryApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [상품 기록](commerce-tdd-product-log.md) |
| 도메인 연결 | STOCK-06, 상품의 재고 소유, 사용자 잔액·주문 상태·스냅샷, 검증 후 변경 및 실패 상태 보존 | done | [상품](commerce-tdd-product-log.md) · [포인트](commerce-tdd-point-like-log.md) · [주문](commerce-tdd-order-log.md) |
| 잠금·동시성 | READ_COMMITTED·정해진 잠금 순서·3초 타임아웃·자동 재시도 없음·409. API-23·24와 좋아요 중복의 실제 DB 경합 | done | [브랜드 경합·실제 타임아웃·교착](commerce-tdd-admin-brand-http-log.md) · [주문 경합 7개](commerce-tdd-order-log.md) · [동시 좋아요](commerce-tdd-point-like-log.md) |
| 트랜잭션·롤백 | 주문·항목·재고·잔액의 부분 반영 없음. 실제 SQL flush 후 호출자 트랜잭션 실패와 전체 상태 비교 | done | [OrderServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderServiceIntegrationTest.java) · [좋아요 롤백](commerce-tdd-point-like-log.md) |
| 재요청 | 좋아요/취소/재삭제는 추가 변경 없음, 충전/주문 생성은 성공마다 새 반영, 확정은 한 번만 차감 | done | [브랜드](commerce-tdd-admin-brand-http-log.md) · [상품](commerce-tdd-product-log.md) · [포인트·좋아요](commerce-tdd-point-like-log.md) · [주문](commerce-tdd-order-log.md) |
| HTTP·권한 경계 | 25개 API의 입출력·오류, 관리자 Security·CSRF, 본인 접근, 숫자·엄격 JSON·프레임워크 오류. 충전부터 주문 조회 연결과 기존 Example 계약 유지 | done | [API별 증거](#api-25개-완료-증거) · [공통 HTTP·JSON 보완](commerce-tdd-admin-brand-http-log.md) |
| 전체 회귀 | 최종 코드의 :apps:commerce-api:check 성공. 실패·오류·건너뜀 0개 | done | [506개 최종 검사](#최신-통합-검사) |
| Checkstyle·ArchUnit | 전체 Java 모듈 main/test/testFixtures 위반0·계층 의존 검사 통과. 규칙 삭제/완화 없음 | done | [최종 검사](#최신-통합-검사) |
| 문서·코드 일치 | 정책→API→TDD→구현·실행 기록 대조, 로컬 링크·앵커·diff 확인. 운영 적용·초기화 방법 기록 | done | [전체 설계](commerce-erd-draft.md) · [TDD 계획](commerce-tdd-plan.md) · [운영 안내](commerce-schema-operations.md) |
| 최종 범위 대조 | API 25행·TDD 24행·통합 12행의 완료 증거 확보 | done | 이 문서의 전체 표와 [최신 통합 검사](#최신-통합-검사). 과제 피드백의 세 보완과 최종 회귀 완료. 이 기능·검증 범위에 남은 항목 없음 |

동시 실패의 상태 보존은 다른 요청의 성공 효과를 취소하는 뜻이 아니다. 단독 실패는 전후 값 동일, 경합 실패는 실패 요청의 추가 효과 없음과 전체 최종 상태를 검증한다. 예를 들어 다른 조건이 충족된 상태에서 재고 5인 상품에 두 주문이 각 4개 확정을 요청하면 성공 1건·최종 재고 1·실패 주문 DRAFT를 확인한다.

전체 설계의 버드뷰·계층 의존·도메인 관계·세 대표 흐름은 [하나의 설계 문서](commerce-erd-draft.md)에 유지한다. 기존 테스트와 역사적 로그를 보존하며, 최종 미완료 항목이 있으면 이유와 다음 행동을 증거 칸에 남긴다.
