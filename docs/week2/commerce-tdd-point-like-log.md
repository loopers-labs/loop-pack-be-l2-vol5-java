# 포인트·좋아요 TDD 실행 기록

2026-09-18 사용자가 남은 계약 제안을 승인한 뒤 C04~C08을 구현했다. 포인트는 원 단위 정수 `long`/`BIGINT`이며 초기 잔액은 0이다. 성공한 충전 요청마다 잔액을 더하고, 좋아요 중복 등록·이미 없는 관계 취소는 추가 변경 없이 성공한다. 이 기록의 개별 통과 결과와 전체 회귀·운영 DDL 완료 여부는 구분한다.

## 책임과 파일

| 파일 | 책임 |
| --- | --- |
| [User](../../apps/commerce-api/src/main/java/com/loopers/domain/user/User.java), [PointsException](../../apps/commerce-api/src/main/java/com/loopers/domain/user/PointsException.java) | 잔액 0 초기화, 양수 충전·차감, `Math.addExact` 합산 초과 거절, 잔액 부족 검사와 변경 없는 차감 사전 검증 |
| [UserRepository](../../apps/commerce-api/src/main/java/com/loopers/domain/user/UserRepository.java), [UserJpaRepository](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/user/UserJpaRepository.java) | 명시한 사용자 ID 저장·조회, 사용자 행의 쓰기 잠금과 기존 행을 보존하는 fixture INSERT |
| [FixtureUserInitializer](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/user/FixtureUserInitializer.java) | 기존 단일 식별 Map에서 ID를 받아 시작 시 누락 행만 잔액 0으로 적재. 요청 시 자동 생성하지 않음 |
| [PointService](../../apps/commerce-api/src/main/java/com/loopers/application/user/PointService.java), [PointController](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/point/PointController.java) | 사용자 식별·저장 잔액 조회·충전 트랜잭션과 C07/C08의 엄격한 정수 입력·응답 연결 |
| [ProductLike](../../apps/commerce-api/src/main/java/com/loopers/domain/like/ProductLike.java), [ProductLikeRepository](../../apps/commerce-api/src/main/java/com/loopers/domain/like/ProductLikeRepository.java) | 사용자·상품 관계와 생성 시각, 사용자/상품 FK, `UNIQUE(user_id, product_id)`, 관계 집계·물리 삭제 계약 |
| [ProductLikeJpaRepository](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/like/ProductLikeJpaRepository.java) | 실제 MySQL 관계 저장·중복 제약·삭제·집계 |
| [LikeService](../../apps/commerce-api/src/main/java/com/loopers/application/like/LikeService.java), [LikeController](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/like/LikeController.java) | C04/C05 등록·취소, C06 본인 확인·목록. 상품 조회 저장소의 좋아요 목록·집계 결과를 재사용 |

식별 문자열은 기존 `alice → 1/CUSTOMER`, `bob → 2/CUSTOMER`, `admin → 3/ADMIN` Map 한 곳에 유지한다. 관리자 주문 응답의 역매핑도 같은 Map을 조회하며 별도 매핑을 추가하지 않는다. 기존 식별·권한 테스트의 기대값은 유지하고, 패키지 전체를 스캔하는 단독 Spring 연결 테스트에는 새 JPA 의존을 위한 저장소 대역만 추가했다.

## 상태 변경과 잠금

- 충전은 사용자 행을 잠근 후 검증·변경한다. `현재 잔액 + 충전액`이 Long 최댓값을 넘으면 기존 잔액을 보존한다. `deduct`와 `validateDeduction`은 같은 양수·잔액 부족 규칙을 공유한다.
- 초기화는 사용자 ID 오름차순으로 `INSERT ... ON DUPLICATE KEY UPDATE id = id`를 사용한다. 기존 사용자 잔액·감사 시각을 덮어쓰지 않으며 동시 초기화에서도 기존 행을 새 잔액 0으로 교체하지 않는다.
- 좋아요 변경은 사용자 → 상품 순서로 행을 잠근다. 관계 INSERT의 사용자 FK 확인도 부모 행 잠금을 사용하므로, 상품만 먼저 잠그면 사용자 → 상품 순서인 주문 확정과 잠금 순서가 엇갈릴 수 있다. 승인된 전체 잠금 순서를 관계 저장에도 적용했다.
- 좋아요 등록은 상품 잠금 후 상품·브랜드의 미삭제 상태를 확인한다. 취소는 삭제 상품도 허용하지만 실제 상품 행이 없으면 `PRODUCT_NOT_FOUND`다. 취소는 본인 관계 행만 실제 삭제한다.
- 변경 트랜잭션은 `READ_COMMITTED`다. 읽기 전용 목록은 본인 관계 중 미삭제 상품·브랜드만 포함하며 관계 생성 시각·관계 ID 내림차순으로 페이지를 구성한다.

고객 HTTP는 형식 검증 후 사용자 식별을 수행한다. 따라서 미등록 사용자와 잘못된 충전액을 함께 보내도 입력 오류가 우선한다. application 서비스의 사용자 조회와 HTTP 입력 검증의 책임을 구분했다.

## 실제 Red → Green

| 단계 | 실제 Red | Green 확인 |
| --- | --- | --- |
| 포인트 도메인 | `UserPointsTest`의 `User`·`PointsException` 미구현으로 컴파일 실패 | 양수·반복 충전·Long 경계·잔액 부족·실패 후 상태 보존 구현. 사전 검증 추가 전 `validateDeduction` 누락도 별도 컴파일 Red 확인 |
| 포인트 저장·application | 테스트를 먼저 작성하고 사용자 JPA·초기화·서비스를 연결 | MySQL 반복 충전, 최댓값 저장, 실패 시 행·시각 보존, flush 후 롤백, 동시 충전·매핑된 사용자 행 누락 검증 통과 |
| 포인트 HTTP | C07/C08 컨트롤러가 없는 상태에서 30개 모두 실패 | 컨트롤러·엄격한 입력 검증을 연결하여 30개 통과 |
| 좋아요 저장소 | `ProductLike`·`ProductLikeRepository` 미구현으로 컴파일 실패 | FK·유일 제약·집계·본인 관계 물리 삭제·재등록 4개 통과 |
| 좋아요 HTTP·application | C04/C05/C06 미구현으로 HTTP 25개 모두 실패 | 등록·중복·취소·재취소·재등록, 삭제 상품·브랜드 처리, 본인 목록·페이지·형식 오류와 동시 중복 25개 통과 |

## 검증 결과

아래 수치는 해당 클래스의 실제 통과 결과다. 동일한 통합 실행에서 구현 중인 주문 테스트의 Red도 함께 실행했으므로 이 표만으로 전체 빌드 성공을 주장하지 않는다.

| 테스트 클래스 | 확인한 결과 |
| --- | --- |
| [UserPointsTest](../../apps/commerce-api/src/test/java/com/loopers/domain/user/UserPointsTest.java) | 13개 통과 |
| [PointServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/user/PointServiceIntegrationTest.java) | 18개 통과 |
| [FixtureUserInitializerIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/user/FixtureUserInitializerIntegrationTest.java) | 2개 통과 |
| [PointApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/point/PointApiE2ETest.java) | 30개 통과 |
| [ProductLikeRepositoryIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/like/ProductLikeRepositoryIntegrationTest.java) | 4개 통과 |
| [LikeApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/like/LikeApiE2ETest.java) | 25개 통과 |
| [LikeServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/like/LikeServiceIntegrationTest.java) | INSERT·DELETE 이후 강제 실패의 실제 롤백 검증 2개 통과 |

기존 사용자 식별 테스트는 `UserResolverTest` 11개, `UserIdentityWiringTest` 1개, `FixtureUserIdentityRepositoryTest` 13개가 통과했다. 성공·실패 후 저장 상태를 확인하고, 테스트·기대값·계층 의존 규칙은 삭제하거나 완화하지 않았다.

## 후속 통합 확인

좋아요 application 롤백 2개를 후속 실행에서 확인했고 Checkstyle main/test 위반은 0개였다. 전체 ArchUnit·회귀 검사와 마지막 초기화 순서 정리의 재검증은 최종 실행 결과를 확인한 뒤 갱신한다. 주문 확정과의 경합·잠금 타임아웃 응답·운영 DDL은 전체 완료 체크리스트에서 별도 증거로 추적한다. 이 기록은 해당 범위를 자동으로 완료 처리하지 않는다.
