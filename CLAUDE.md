# 프로젝트 작업 규칙

AI와 함께 이 저장소에서 기능을 구현할 때 따르는 규칙입니다.

---

## 합의한 기준을 따른다

- 설계 문서(`docs/`)에 합의한 계약·기대값·패키지 의존을 따른다.
- 설계 문서에 없거나 결정되지 않은 정책은 구현하기 전에 먼저 질문한다. 임의로 정하지 않는다.

## 구현 전에 먼저 제안한다

- 이번 기능에서 변경할 책임, 파일, 관련 테스트를 먼저 제안하고 확인을 받는다.

## 작게 구현하고 결과를 확인한다

- 작은 기능 하나를 구현하고 diff와 관련 테스트·lint·ArchUnit 결과를 확인한 뒤 다음 기능으로 넘어간다.
- 검사 명령

```bash
./gradlew :apps:commerce-api:test --tests '*ArchitectureTest'
./gradlew :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest
./gradlew :apps:commerce-api:check
```

## 검사를 우회하지 않는다

- 검사를 통과시키기 위해 테스트·기대값·규칙을 삭제하거나 완화하지 않는다.
- 정책·검사 기준 변경이나 범위 밖 개편이 필요하면 이유와 영향을 설명하고 확인을 받는다.

## 주석은 간결하게 쓴다

- 주석은 장황하게 쓰지 말고 핵심만 간결하게 적는다.
- 코드를 보면 알 수 있는 내용은 쓰지 않고, "왜 그렇게 했는지"가 필요한 경우에만 쓴다.
- 한 줄로 충분하면 한 줄로 쓴다.

---

## 패키지 의존 규칙

`ArchitectureTest` 가 검사한다.

| 계층 | 의존하면 안 되는 계층 |
|---|---|
| `domain` | `interfaces`, `application`, `infrastructure` |
| `application` | `interfaces`, `infrastructure` |
| `interfaces` | `infrastructure` |

## 패키지 구조

기능 패키지 안을 종류별 하위 패키지로 나눈다. 테스트 패키지도 같은 구조를 따른다.

### domain/{기능}/

| 하위 패키지 | 두는 것 | 예 |
|---|---|---|
| `entity` | `@Entity` | `Payment`, `Order` |
| `enums` | enum (상태 · 사유 · 오류 코드) | `PaymentStatus`, `PaymentErrorCode` |
| `vo` | 값 객체 · 결과 타입 (record, sealed interface) | `ProviderTransaction`, `PaymentCreateResult` |
| `repository` | 저장소 인터페이스 | `PaymentRepository` |
| `gateway` | 외부 시스템 포트 인터페이스와 그 요청 타입 | `PaymentGateway`, `PaymentGatewayRequest` |
| `service` | 도메인 서비스 | `PaymentService` |

- 패키지 이름에 `enum` 은 쓸 수 없다(예약어). `enums` 로 쓴다.
- 다른 패키지에서 쓰지 못하게 숨겨야 하는 타입(package-private)은 종류와 관계없이 그 타입을 쓰는 클래스와 같은 패키지에 둔다. 예: `LookupEvent` 는 `Payment` 와 같은 `entity` 에 둔다.

### application/{기능}/

| 하위 패키지 | 두는 것 |
|---|---|
| `facade` | 유스케이스 조율(`~Facade`), 트랜잭션 단계(`~Steps`), 재시도(`~Retrier`) |
| `dto` | 결과 · 명령 타입(`~Info`, `~Command`) |

### infrastructure/{기능}/

| 하위 패키지 | 두는 것 |
|---|---|
| `persistence` | `~JpaRepository`, `~RepositoryImpl` |
| `client` | 외부 시스템 adapter와 그 설정 |
| `cache` | Redis 캐시 구현 |

### interfaces/api/{기능}/

파일 수가 적어(Controller · ApiSpec · Dto) 나누지 않는다.

## Lombok

- 엔티티 · 값 객체는 클래스에 `@Getter` 를 단다.
- 아래 경우만 getter를 직접 작성하거나 만들지 않는다.
  - 공개하면 안 되는 필드: getter를 만들지 않는다. 예: `Product.brand` (D-36)
  - 그대로 내보내면 안 되는 컬렉션: 읽기 전용으로 감싸 직접 작성한다. 예: `Order.items`
- `@Setter` 는 쓰지 않는다. 상태는 의미 있는 행동 메서드로만 바꾼다.
