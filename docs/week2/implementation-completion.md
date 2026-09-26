# commerce-api 구현 기록

2026-09-18에 Green 구현 뒤 남아 있던 Refactor, 설계 대조, 검사 범위 연결을 마무리한 기록이다. 요구사항, Red 테스트, 검사 규칙은 수정하거나 완화하지 않았다.

## 변경한 책임과 파일

### 전체 Java 모듈 Checkstyle 연결

- 루트 [`build.gradle.kts`](../../build.gradle.kts)에서 모든 하위 모듈에 Checkstyle 10.26.1과 저장소 공통 규칙을 적용했다.
- 기존 코드에서 새 검사 범위에 걸린 두 import만 정리했다.
  - `modules/kafka/.../KafkaConfig.java`: 별표 import를 실제 사용하는 타입의 개별 import로 바꿨다.
  - `apps/commerce-batch/.../DemoJobE2ETest.java`: 사용하지 않는 import를 제거했다.
- Kafka 설정값, Bean 구성, 배치 테스트의 동작은 바꾸지 않았다.

### 대표 TDD 사례 — 재고 차감

대표 규칙은 `Stock`의 재고 차감이다.

| 단계 | 커밋·변경 | 확인한 내용 |
| --- | --- | --- |
| Red | `04e1d73` | 재고 5개에서 6개 차감 시 `INSUFFICIENT_STOCK`으로 거절하고 기존 재고 5개를 유지하며, 2개 차감 시 새 재고 3개를 반환하는 테스트를 먼저 작성했다. 당시 `Stock.decrease()`는 기존 객체를 그대로 반환해 테스트가 의도한 이유로 실패했다. |
| Green | `0f87e24` | 차감 수량과 보유 재고를 검사하고 성공하면 차감된 새 `Stock`을 반환하는 최소 구현을 추가했다. Red 테스트의 기대값은 변경하지 않았다. |
| Refactor | `a4a03da` | 양수 조건과 재고 충분 조건을 `ensurePositive`, `ensureSufficient`로 분리했다. `Stock`을 `Product`가 소유하는 JPA VO로 정리하면서 새 값을 반환하는 불변성은 유지했다. |

Red 테스트와 오류 코드는 바꾸지 않았다. Refactor 전후 모두 `Stock`은 불변이며 성공하면 새 값을 반환하고, 실패하면 기존 값을 유지한다.

### 설계와 구현 대조

- `interfaces → application → domain ← infrastructure` 의존 방향은 `ArchitectureTest`로 확인했다.
- 문서의 애그리거트와 구현을 대조했다.
  - 사용자: `User`가 `Point`를 가진다.
  - 상품: `Product`가 `Stock`을 가진다.
  - 주문: `Order`가 `OrderItem`과 `PaymentResult`를 가진다.
- 여러 애그리거트나 저장된 관계를 확인하는 도메인 서비스가 구현에 존재하는지 확인했다.
  - 브랜드 삭제 가능 여부, 브랜드·상품 이름 중복 여부, 좋아요 중복 여부, 주문 확정
- 좋아요 등록이 저장소를 직접 조회하던 부분을 설계에 적힌 `LikeDuplicationChecker`를 사용하도록 정리했다. 판단 결과와 API 동작은 바뀌지 않았다.
- `R-LIKE-02`를 애플리케이션의 선조회에만 의존하지 않도록 `product_like`의 `(user_id, product_id)`에 복합 유니크 제약을 추가했다.
  - Red: `LikeRepositoryIntegrationTest`의 세 테스트 중 새로 추가한 중복 저장 시나리오가 실패해 DB가 같은 고객·상품의 중복 관계를 허용하는 것을 확인했다.
  - Green: `Like`의 테이블 매핑에 `uk_product_like_user_product` 제약을 추가해 DB가 중복 관계를 거절하도록 했다.
  - `GenerationType.IDENTITY`에서는 중복 INSERT 예외가 `flush`보다 `persist`에서 먼저 발생할 수 있으므로, 테스트는 두 저장과 flush를 포함한 영속화 작업 전체가 `PersistenceException`으로 거절되는지 확인한다.
- 과제 기능이 아닌 starter의 `Example` API·도메인·저장소와 전용 테스트를 제거했다. Example을 사용하던 공통 응답 계약 테스트는 고객 브랜드 API로 옮겨 성공·입력 오류·자원 미존재·미매핑 응답 검증을 유지했다.
- 주문 품목 로딩 방식은 구현 중 생긴 판단이어서 [ADR-007](./decisions.md#adr-007-주문을-조회할-때-품목을-함께-로딩한다)에 대안과 비용을 기록했다.

## 실행한 검사

아래 결과는 2026-09-18 실행 시점의 기록이며, 이후 테스트 증감과 구분한다.

```bash
./gradlew :apps:commerce-api:test --tests '*Stock*Test'
./gradlew :apps:commerce-api:test --tests '*ArchitectureTest'
./gradlew :apps:commerce-api:clean :apps:commerce-api:check
```

| 검사 | 결과 |
| --- | --- |
| `StockTest`, `ProductTest`, `LikeDuplicationCheckerTest`, `LikeUseCaseIntegrationTest` | 성공 |
| `./gradlew :apps:commerce-api:check --rerun-tasks` | 성공 |
| commerce-api 테스트 결과 | 442개, 실패 0, 오류 0, skip 0 |
| `ArchitectureTest` | 1개, 실패 0 |
| commerce-api main·test Checkstyle | 성공 |
| `./gradlew check --continue` | 성공, 전체 하위 모듈 Checkstyle 포함 |
| `git diff --check` | 성공 |

## 제출 설명에 사용할 요약

- 대표 TDD 사례: 재고 차감 규칙을 Red → Green → 조건 검증 메서드 분리 Refactor 순서로 진행했다.
- 설계에서 바뀐 판단: 주문 상세 응답은 품목을 포함하고 요약 응답도 품목 수를 계산한다. Open EntityManager in View를 끈 환경이므로 주문과 품목을 함께 로딩하며, 대안과 재검토 조건은 ADR-007에 남겼다.
- 검사: commerce-api 테스트 442개, Checkstyle, ArchUnit과 루트 전체 `check`가 통과했다.
