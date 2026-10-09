# 테스트 경량화 결과

[레이어별 테스트 문서](../test/) · [결정 기록](context-notes.md)

작업 브랜치: `volume-3/refacto-test-slim` (`volume-3/refacto`의 `be9cf1d`에서 분기, 완료 후 fast-forward 병합)

## 배경

전체 `check`가 무겁고, 같은 시나리오를 여러 층에서 반복 검증하며, 거의 모든 테스트가 Docker를 필요로 했다.
조사해 보니 비용은 대부분 다음 네 곳에서 나왔다.

- Spring 컨텍스트 6개. 테스트 클래스마다 다른 `@MockitoSpyBean`을 선언해 기본 컨텍스트 2개(MOCK, RANDOM_PORT) 위에 4개가 더 떴다. 컨텍스트마다 스키마를 새로 만들고 커넥션 풀을 따로 가졌다.
- 컨테이너 기동. MySQL(최근 기록 27.8초)에 더해 commerce-api가 쓰지 않는 Redis 컨테이너까지 떴다.
- 테스트마다 11개 테이블 전체 TRUNCATE. `@Transactional` 롤백과 TRUNCATE를 함께 쓰는 클래스도 7개 있었다.
- E2E가 통합 테스트에서 이미 검증한 DB 상태를 다시 검증했다.

## 결정

사용자와 문답으로 합의한 기준이다.

- 다른 층에서 같은 내용을 검증하는 테스트만 지운다. R02 필수 시나리오(동시성 6건, 롤백 검증)는 모두 유지한다.
- 무거운 테스트는 지우지 않고 태그로 나눈다. 빠른 기본 실행(`test`)에서는 빼고, `check`에서는 모두 실행한다.
- 로그 설정과 `-Xshare:off`는 바꾸지 않는다.
- example 스캐폴딩 테스트는 삭제하지 않고 태그로 기본 실행에서만 뺀다.

## 바뀐 것

| 영역 | 변경 |
|---|---|
| Spring 컨텍스트 | 공용 메타 어노테이션 `@IntegrationTest`(MOCK)·`@E2ETest`(RANDOM_PORT)를 `com.loopers.support.test`에 추가했다. 둘 다 같은 spy 집합(OrderRepository, ProductJpaRepository, JdbcLikeCountAggregationDao)을 타입 수준 `@MockitoSpyBean`으로 선언하므로 컨텍스트가 6개에서 2개로 줄었다. 필드 spy를 쓰던 3개 클래스는 공용 spy를 `@Autowired`로 받는다. |
| 태그와 태스크 | `slow`: ConfirmOrderConcurrencyIntegrationTest, BrandFindForDeletionLockIntegrationTest, StockLostUpdateControlGroupTest. `example`: ExampleServiceIntegrationTest, ExampleV1ApiE2ETest, ContractClassificationTest. `test`는 두 태그를 제외하고, 새 `slowTest`가 두 태그만 실행하며, `check`는 둘 다 실행한다. |
| Redis | commerce-api 테스트 의존에서 redis testFixtures를 뺐고 테스트 프로필에서 Redis 헬스 체크를 껐다. 운영 의존은 그대로다. |
| DB 정리 | `DatabaseCleanUp`이 비어 있는 테이블은 TRUNCATE하지 않는다. `@Transactional`로 롤백되는 7개 클래스는 TRUNCATE 정리를 없앴다. 트랜잭션 밖에서 커밋하는 잠금 테스트 2개는 정리를 유지한다. |
| 컨테이너 재사용 | MySQL 테스트 컨테이너에 `withReuse(true)`를 추가했다. `~/.testcontainers.properties`에 `testcontainers.reuse.enable=true`가 있을 때만 동작하고, 없으면 이전과 같다(CI 영향 없음). |
| 중복 테스트 | 아래 목록. |

### 삭제·축소한 테스트

| 대상 | 처리 | 대신 검증하는 테스트 |
|---|---|---|
| ConfirmOrderServiceTest 6건(재고·포인트 부족, 재확정, 상품 삭제, 없는 주문, 정상 확정) | 삭제 | OrderConfirmationPolicyTest, ConfirmOrderIntegrationTest, OrderApiE2ETest |
| OrderApiE2ETest 확정 4건 | DB 상태 단언 제거, HTTP 상태·에러 코드·응답 본문만 | ConfirmOrderIntegrationTest |
| OrderApiE2ETest 500 롤백 | 삭제, spy 제거 | 롤백은 ConfirmOrderSqlRollbackIntegrationTest, 500 응답은 ApiControllerAdviceTest(추가) |
| OrderApiE2ETest 상세 조회·없는 사용자 404 | 삭제 | 같은 클래스의 확정 후 조회 테스트, XUserIdArgumentResolverTest |
| WalletApiE2ETest 잔액 초과, 헤더 누락·형식 오류 4건, 없는 사용자 2건 | 삭제 | WalletServiceTest, WalletTest, XUserIdArgumentResolverTest, LikeApiE2ETest의 1건씩 |
| WalletApiE2ETest 음수 금액 | DB 상태 단언 제거 | WalletServiceTest |
| OrderRepositoryIntegrationTest#enforcesOneOrderRecordPerOrder | 삭제(유일성을 실제로 검증하지 못함) | #savesConfirmedOrder_withCascadedOrderRecord |
| BrandApiE2ETest 연쇄 삭제 2건 | 삭제 | BrandRepositoryIntegrationTest, BrandTest |
| BrandRepositoryIntegrationTest#findsBrandForDeletion_withAllUndeletedProducts | 삭제 | #savesBrand_propagatesDeleteToAllProducts_andKeepsUnrelatedFields |
| LikeApiE2ETest 중복 등록·없는 관계 취소 | 삭제 | JdbcLikeCommandDaoIntegrationTest, LikeStorageIntegrationTest |
| JdbcLikeQueryDaoIntegrationTest#excludesProducts_deletedViaBrandBulkDelete | 삭제 | #excludesDeletedProducts |

## 결과

| 항목 | 이전 | 이후 |
|---|---|---|
| Spring 컨텍스트 | 6개 | 2개(`test`, `slowTest` 각 JVM에서 MOCK 1 + RANDOM_PORT 1) |
| Redis 컨테이너 | 매 실행 기동 | 기동하지 않음 |
| 테스트 수 | 247건(R02 최종 check) | `test` 211건 + `slowTest` 17건 = 228건, 실패 0 |
| 전체 `check` 시간 | 측정 기록 없음 | 4분 33초 |

이전의 전체 `check` 시간은 기록해 두지 않아서 직접 비교할 수 없다. 참고로 이전에는 주문 관련 일부 테스트만 돌려도 컨텍스트 기동에 67.6초·15.0초·7.6초가 각각 들었다.
테스트 수에는 이번 작업 사이에 추가된 OrderRecord 관련 테스트가 섞여 있어, 247 → 228이 순수 삭제 수와 같지는 않다.

## 실행 방법

```bash
./gradlew :apps:commerce-api:test
```
빠른 기본 실행이다. `slow`·`example` 태그는 제외된다.

```bash
./gradlew :apps:commerce-api:slowTest
```
동시성·잠금 테스트와 example 테스트만 실행한다.

```bash
./gradlew :apps:commerce-api:check
```
전체 실행이다. `test`와 `slowTest`, Checkstyle을 모두 돈다.

로컬에서 MySQL 컨테이너를 실행 간에 재사용하려면 홈 디렉터리의 `.testcontainers.properties`에 `testcontainers.reuse.enable=true`를 추가한다.
git worktree에서 테스트를 돌릴 때는 git에서 제외된 `apps/commerce-api/src/test/resources/docker-java.properties`(`api.version=1.44`)를 복사해야 한다. 없으면 Testcontainers가 `BadRequestException (Status 400)`으로 Docker 연결에 실패한다.

## 남은 공백

- 브랜드·상품·좋아요 application 서비스에는 단위 테스트가 없어 해당 분기는 E2E만 검증한다.
- QueryDslProductQueryDao(상품 정렬·페이징 SQL)는 통합 테스트가 없어 ProductApiE2ETest만 검증한다.
