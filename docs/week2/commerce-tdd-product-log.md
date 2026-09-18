# 상품 도메인·영속성·관리자 변경·조회 TDD 실행 기록

2026-09-18 승인된 P08·P12·P13과 API 계약을 기준으로 상품의 상태, 실제 MySQL 저장, 관리자 변경, 고객·관리자 조회를 연결했다. 이 기록의 기능 범위는 C02·C03, A06~A11과 주문 확정에서 재사용하는 재고 사전 검증이다. 전체 API·주문·좋아요·운영 DDL·동시성 검증의 완료를 의미하지 않는다.

## 책임과 구현

| 계층·파일 | 책임 |
| --- | --- |
| [Product](../../apps/commerce-api/src/main/java/com/loopers/domain/product/Product.java), [ProductStock](../../apps/commerce-api/src/main/java/com/loopers/domain/product/ProductStock.java) | 상품명 strip·1~100 Unicode 코드 포인트, 1원 이상 Long 가격, 브랜드 유지, 내부 재고 수량, 논리 삭제와 삭제 후 변경 금지 |
| [ProductRepository](../../apps/commerce-api/src/main/java/com/loopers/domain/product/ProductRepository.java), [JPA 구현](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/product/ProductRepositoryImpl.java) | 생성·조회·행 잠금·브랜드 ID 조회·미삭제 상품 존재 조회. 재고 0도 브랜드 삭제를 막는 상품에 포함 |
| [AdminProductService](../../apps/commerce-api/src/main/java/com/loopers/application/product/AdminProductService.java) | 관리자 확인 → 브랜드 잠금 → 상품 잠금 → 상태 변경·저장을 READ_COMMITTED 트랜잭션으로 수행. 신규 등록도 브랜드 잠금에 참여 |
| [ProductQueryRepository](../../apps/commerce-api/src/main/java/com/loopers/domain/product/ProductQueryRepository.java), [조회 구현](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/product/ProductQueryRepositoryImpl.java) | JPA EntityManager의 JPQL 조인·좋아요 집계·필터·정렬·페이지와 전체 건수. domain 소유 ProductSummary 반환 |
| [ProductQueryService](../../apps/commerce-api/src/main/java/com/loopers/application/product/ProductQueryService.java), [AdminProductQueryService](../../apps/commerce-api/src/main/java/com/loopers/application/product/AdminProductQueryService.java) | 고객 공개 조회의 삭제 제외, 관리자 권한과 삭제 행 포함, application 결과 변환 |
| [ProductController](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/product/ProductController.java), [AdminProductController](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/product/AdminProductController.java) | C02·C03·A06~A11 HTTP 입력·응답. 관리자 공통 필터는 본문·경로 바인딩보다 먼저 권한 검사 |

Product는 BaseEntity를 상속하지 않고 생성·수정·삭제 시각을 직접 매핑한다. `@Embedded ProductStock` 하나가 저장되는 재고를 소유하며 변경 가능한 재고 객체를 외부에 노출하지 않는다. `validateStockDeduction`은 삭제 상태·수량·재고를 검사하기만 하고 수량을 변경하지 않으며, 실제 차감도 같은 ProductStock 검증을 재사용한다.

수정은 이름과 가격만 바꾸고 브랜드와 재고를 유지한다. 이름과 가격을 모두 검증한 후 대입하여 가격 오류가 먼저 검증한 이름까지 부분 변경하지 않는다. 재고 설정은 증감량이 아닌 최종 수량이다. 재삭제는 최초 deletedAt·updatedAt과 관계 행을 보존한다. 관리자 변경 응답의 좋아요 수는 잠금을 유지하는 동일 트랜잭션에서 실제 관계 집계로 얻는다.

조회는 좋아요 0인 상품도 LEFT JOIN으로 포함하고 전체 일치 결과를 정렬한 다음 페이지를 자른다. ProductSummary의 JPQL 생성자는 Brand.name 값객체를 받아 응답용 문자열로 변환한다. 고객은 상품과 브랜드가 모두 미삭제인 행만, 관리자는 삭제 행도 조회한다. 내 좋아요 조회 포트는 본인 관계의 생성 시각·ID 내림차순을 적용하며 삭제 상품·브랜드를 목록에서만 제외한다. 아주 큰 page는 long으로 offset을 계산하고 전체 건수를 넘으면 건수를 유지한 빈 페이지를 반환한다.

## 실제 Red → Green과 리팩터링

| 증분 | 실제 Red | Green·검토 결과 |
| --- | --- | --- |
| 상품 모델·DB 왕복 | ProductTest·ProductRepositoryIntegrationTest를 먼저 작성한 실행에서 Product·ProductException·저장소 메서드 미구현 컴파일 오류 확인. 같은 컴파일에 진행 중인 User·PointsException 누락도 함께 보고됨 | Product·ProductStock 매핑·JPA 저장소 구현 후 모델 13개, 실제 MySQL 영속성 4개 통과 |
| 관리자 변경 application | AdminProductServiceIntegrationTest 작성 후 서비스·결과·조회 오류 타입 누락 15개 컴파일 오류 확인 | 실제 권한·생성·수정·최종 재고·삭제·재삭제·오류 후 DB 보존 5개 통과 |
| 상품 HTTP | ProductApiE2ETest 18개가 미구현 경로의 404로 실패 | 관리자·고객 컨트롤러 연결 후 18개 통과 |
| 주문용 재고 사전 검증 | ProductTest의 validateStockDeduction 호출 4곳이 메서드 누락으로 컴파일 실패 | ProductStock 검증을 부작용 없는 메서드로 추출. ProductTest가 14개로 증가했고 기존 ProductStockTest 19개도 통과 |
| 상품 조회·집계 application | ProductQueryServiceIntegrationTest 작성 후 고객·관리자 조회 서비스 누락 2개 컴파일 오류 확인 | JPQL 집계와 결과 변환 구현 후 7개 통과. 관리자 변경 응답도 동일 트랜잭션의 실제 집계를 사용하도록 연결 |
| HTTP 조회 회귀 | API-02·03 fixture의 실제 순서·전체 건수, 고객 상세 응답을 검증 | ProductQueryApiE2ETest 3개 통과 |
| HTTP 경계 보강 | 구현된 계약에 실제 상품 행을 준비해 입력·저장 보존 경계를 추가. 인위적인 Red는 만들지 않음 | ProductBoundaryApiE2ETest 20개 통과. 음수·타입·정수 범위 오류, 최종 재고 0/상한, Unicode 이름·Long 가격 상한, 동일 404 메시지 확인 |
| 호환성·책임 검토 | ProductRepository 확장으로 브랜드 삭제 단위 대역의 구현이 필요해짐 | 기존 기대값을 바꾸지 않고 명시적 테스트 대역으로 연결. BrandDeletionServiceTest 7개 통과 |

컴파일 실패는 해당 타입·메서드가 없었던 구현 전 실패다. 업무 assertion 실패로 표현하지 않는다. 이미 통과하는 경계 사례를 인위적으로 실패시키거나 기대값을 완화하지 않았다.

## 검증된 사례

| 테스트 | 실제 통과 수 | 핵심 근거 |
| --- | ---: | --- |
| [ProductTest](../../apps/commerce-api/src/test/java/com/loopers/domain/product/ProductTest.java) | 14 | 이름·가격·재고 경계, 내부 상태 보존, 삭제 상태, 사전 검사 무변경 |
| [ProductRepositoryIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/product/ProductRepositoryIntegrationTest.java) | 4 | Unicode·Long/Integer 상한의 실제 DB 왕복, 삭제 포함 조회, 품절 상품 존재, 변경 감지와 트랜잭션 롤백 |
| [AdminProductServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/product/AdminProductServiceIntegrationTest.java) | 5 | 관리자 우선, 미삭제 브랜드만 신규 등록, 이름·가격·재고 오류 후 모든 저장 컬럼 보존, 재삭제 멱등 |
| [ProductQueryServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/product/ProductQueryServiceIntegrationTest.java) | 7 | 집계 후 페이지, 세 정렬·동률, 삭제 조건, 관리자 조회, 본인 좋아요 정렬·건수, 변경 응답의 실제 좋아요 수 |
| [ProductApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) | 18 | HTTP 생성·수정·재고·삭제와 재삭제, 공개 상세, 입력·권한·없음 오류 |
| [ProductQueryApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) | 3 | API-02·03 fixture 정렬·페이지·삭제 브랜드 제외, 브랜드·집계 개수와 공개 필드 |
| [ProductBoundaryApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductBoundaryApiE2ETest.java) | 20 | 기존 상품의 모든 DB 컬럼 보존, 재고 0/Integer 상한, 잘못된 이름·가격, Unicode 100자·Long 상한 HTTP 왕복, 삭제 브랜드 등록 금지와 동일 404 응답 |

위 상품 테스트는 **71개 통과**다. 관련 회귀 ProductStockTest 19개·BrandDeletionServiceTest 7개도 통과했다. 전체 작업의 중간 합동 실행은 175개 중 145개 통과였고, 당시 남은 실패는 아직 구현 전인 좋아요 HTTP 25개·주문 HTTP 5개의 Red였다. 이를 전체 suite 통과로 기록하지 않는다. 실제 MySQL 브랜드 경합 테스트 2개도 별도 검증됐으며 전체 동시성 완료 근거는 최종 체크리스트에 모은다. 경계 보강 후 실행에서 ProductBoundaryApiE2ETest 20개와 관련 브랜드 HTTP·브랜드 경합·좋아요 롤백 테스트가 통과했고 Checkstyle main/test 위반은 0개였다.

재실행 명령은 다음과 같다.

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.domain.product.*' \
  --tests 'com.loopers.infrastructure.product.*' \
  --tests 'com.loopers.application.product.*' \
  --tests 'com.loopers.interfaces.api.product.*' \
  --tests 'com.loopers.domain.brand.BrandDeletionServiceTest' \
  --console=plain -q
```

후속 [전체 스키마 TDD](commerce-tdd-schema-log.md)에서 Product FK·CHECK·인덱스를 포함한 수동 SQL과 기존 브랜드 SQL 42개 검증을 통과했다. 주문·좋아요 연결 이후 경쟁 요청과 전체 회귀·Checkstyle·ArchUnit의 최종 결과는 [전체 완료 체크리스트](commerce-completion-checklist.md)에 별도로 기록한다. 위 명령은 상품 관련 재검증 방법이며 이 문서 작성 후 독립적으로 다시 실행했다는 의미가 아니다.
