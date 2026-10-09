# 2주차 작업 규칙

이 저장소에서 `apps/commerce-api`의 2주차 과제를 작업할 때 이 규칙을 따른다. 규칙의 이유와 예시는 [개발 컨벤션](docs/week2/conventions.md)을 기준으로 한다.

## 작업 순서

학습 범위와 총 7개 PR의 완료 조건은 [개발 계획](docs/week2/development-plan.md)을 따른다.
기준 브랜치는 `volume-2/main`, 작업 브랜치는 `volume-2/pr-번호-작업명`이다.
앞 PR을 검증·병합한 뒤 최신 기준 브랜치에서 다음 브랜치를 만들며 작업 브랜치끼리 직접 병합하지 않는다.
PR 01은 계획 정리·공통 기반·사용자 입력을 함께 다룬다. PR 내부에서는 작은 기능 단위로 TDD와 커밋을 진행한다.
동시성 구현·검증은 다음 학습으로 미룬다. 잠금·버전 검증·재시도·동시 요청 테스트는 이번 범위에서 제외한다.
순차 재확정 거절, DB 유일성 제약, 단일 요청의 전체 롤백은 유지한다.
과제 원문의 인증·인가 요구는 이번 학습 범위에서 제외한다. Spring Security·ADMIN·CSRF·본인 여부 검사를 구현하지 않는다.
`X-USER-ID`는 fixture 사용자 입력이다. 필요한 API에서 누락·형식 오류는 400, 없는 사용자는 404로 처리한다.
좋아요 목록은 경로 userId, 주문 상세·확정은 orderId를 사용한다. 주문 결제 대상은 저장된 userId를 따른다.
상품·브랜드 조회와 관리자용 기능은 사용자 헤더를 요구하지 않는다. API별 입력은 설계 계약을 따른다.

1. 구현 전에 요구사항과 API 계약에서 정상 결과, 대표 오류, 상태 유지 조건을 확인한다.
2. 미정 정책은 임의로 결정하지 않는다. 설계 문서에 질문과 영향을 기록하고 사용자에게 확인한다.
3. 한 번에 하나의 작은 기능을 다룬다. 변경할 책임, 파일 범위, 관련 테스트를 먼저 밝힌다.
4. 대표 도메인 규칙은 테스트를 먼저 작성하고 Red, Green, Refactor 결과를 확인한다.
5. 변경 후 diff와 관련 테스트를 확인하고, 기능이 끝나면 Checkstyle, ArchUnit, 모듈 검사를 실행한다.
6. Green 이후 관련 테스트가 통과한 작은 기능 단위로 커밋한다. 커밋 시점과 메시지 기준은 개발 계획을 따른다.

## 구조와 의존

- User·두 사용자 fixture는 PR 01, 사용자별 초기 잔액 0의 Point는 PR 04에 연결하며 기존 잔액을 초기화하지 않는다.
- PR 02에서 Shopping 소유의 Like 관계·유일성·집계 테이블과 주기 집계를 준비하고 등록·취소는 PR 03에서 연결한다.
- PR 05에서 Pay 소유의 OrderBill 저장·조회 구조를 먼저 준비하고 실제 결제 기록 생성은 PR 06에서 연결한다.
- 선행 저장 구조는 fixture로 검증하며 Context 소유권을 유지한다. 준비용 공개 API·임시 상수 응답을 추가하지 않는다.

- (2주차 당시 규칙, 역사적 기록) 패키지는 `interfaces`, `application`, `domain`, `infrastructure` 아래 Context → 기능 순서로 나눴다.
  Context 이름은 `mall`, `shopping`, `ordering`, `pay`였다. 예: `domain.mall.product`.
- 이후 `volume-3/refacto`에서 패키지 구조가 Context → 종류(kind) 순서로 바뀌었다. domain·application·interfaces는
  `<layer>.<context>.<종류>` 순서를 따른다. 예: `domain.mall.model`, `application.mall.query`,
  `interfaces.api.mall.controller`. infrastructure만 종류가 먼저다: `infrastructure.<종류>.<context>` 순서를 따른다.
  예: `infrastructure.persistence.mall.entity`. 현재 구조와 결정 배경은 [CLAUDE.md](CLAUDE.md)와
  [docs/refactor/](docs/refactor/)(`plan.md`, `context-notes.md`, `result.md`)를 참고한다.
- `interfaces`는 HTTP 입력과 응답 변환을 담당하며 `infrastructure`에 직접 의존하지 않는다.
- `application`은 유스케이스 순서와 트랜잭션을 조율하며 `interfaces`와 `infrastructure` 구현에 의존하지 않는다.
- 신규 `domain`은 순수 Java로 상태·업무 규칙·repository 계약을 소유한다. Spring·JPA·HTTP와 다른 계층에 의존하지 않는다.
- `infrastructure`는 domain 저장 계약과 application QueryDao·조회 타입을 사용한다. application 구현 서비스에는 의존하지 않는다.
- HTTP 정책이나 업무 규칙을 infrastructure에서 중복 구현하지 않는다.
- GET은 조회 전용 Controller → application QueryDao 계약 → infrastructure 구현으로 연결한다. 조회 구현은 QueryDSL
  Projection을 사용한다. 쓰기는 JPA, JdbcClient는 좋아요 집계 같은 배치 작업에만 사용하며 테스트 코드도 쓰지 않는다(3주차 후속 R03·R08에서 변경, 운영 코드는 `LayerArchitectureTest`가 검사한다).
- DAO는 같은 DB의 Context 간 조인과 조회 모델 조합을 담당한다. Controller는 입력·404·ApiResponse 포장을 담당한다.
- 조회 모델은 application의 순수 record이며 별도 HTTP Response 복사 없이 반환한다. domain은 HTTP DTO를 알지 못한다.
- `@XUserId` resolver는 UserQueryDao.findById로 사용자 존재를 검사하고 ID를 반환한다. 형식 오류 시 DAO를 호출하지 않는다.
- 경로 userId는 좋아요 조회 Controller에서 같은 DAO로 검사한다. 쓰기 Service는 사용자 존재를 재검사하지 않는다.
- 쓰기 Service를 직접 호출하는 쪽은 존재하는 사용자 ID를 제공한다. 사용자 검증을 인증·인가로 해석하지 않는다.

## Java 코드

- Java 21과 기존 코드 스타일을 사용하고 `.editorconfig`의 본문 최대 130자와 테스트 파일 예외를 따른다.
- 별표 import와 미사용 import를 허용하지 않는다.
- 의존성은 생성자로 주입한다. Spring 구성요소에는 기존 관례에 맞춰 `@RequiredArgsConstructor`를 사용할 수 있다.
- 도메인 `Product`와 저장 객체 `ProductJpaEntity`를 분리한다. domain은 JPA BaseEntity를 상속하지 않는다.
- 도메인은 숨긴 생성자와 `create` / `restore`로 유효한 상태를 구성하고, 공개 setter 없이 의미 있는 행동으로 변경한다.
- Money·Stock처럼 규칙이 모이는 값부터 값 객체로 분리한다. 실패하는 행동은 메모리 상태도 유지해야 한다.
- 행동별 `ConfirmOrderUseCase` 인터페이스와 `ConfirmOrderFacade` 구현체(컨텍스트 서비스를 순서대로 조율하는 `facade`)를 두고 `execute`로 실행한다.
- 쓰기는 Request → Command → Result → Response, 조회는 Request → Criteria → 조회 record → ApiResponse를 사용한다.
- 쓰기 트랜잭션은 application Service의 `execute`, 조회 readOnly 트랜잭션은 DAO 구현의 공개 메서드에 둔다.
- DAO 상세 조회는 Optional을 반환하며 Controller가 기존 오류로 변환한다. 조회 UseCase·Service는 만들지 않는다.
- 쓰기 성공 응답은 Result를 유지한다. 상품 응답의 likeCount만 DAO로 저장된 집계 값을 읽는다.
- 도메인 변경 후 repository의 `save`를 명시한다. 주문 확정의 모든 변경·기록은 단일 트랜잭션으로 저장한다.
- infrastructure의 전용 EntityMapper로 변환한다. repository는 기존 Entity를 확보하고 Mapper는 저장 메타데이터를 보존한다.
- Mapper 인터페이스·변환 라이브러리는 추가하지 않는다. Mapper에는 업무 판단을 넣지 않는다.
- Request는 형식·구조, domain은 업무 규칙, application은 쓰기의 대상·사용자별 데이터·객체 간 조건을 검사한다.
- domain은 DomainException·업무 코드로 실패를 표현하고 interfaces가 기존 HTTP·ApiResponse 계약으로 변환한다.

## 테스트

- 핵심 업무 규칙은 TDD로 진행한다. domain 단위 테스트는 Spring·DB 없이 정상·경계·실패·실패 후 상태를 엄격히 검증한다.
- 0·정확한 필요량·1 부족·최댓값·계산 초과, 중복 품목·삭제 상태·재확정을 포함한다.
- UseCase 단위 테스트는 실제 도메인과 mock repository/DAO로 협력·사용자별 데이터·순서·실패 시 후속 처리 중단을 검증한다.
- DAO는 실제 DB로 조회·필터·페이지·집계를 검증하고 resolver는 정상·400·404·잘못된 입력의 DAO 미호출을 검증한다.
- 전체 롤백은 실제 DB 통합 테스트로 검증한다. 복잡한 Mapper 변환은 스냅샷·품목·메타데이터 보존을 확인한다.
- repository 통합 테스트는 필요할 때 `flush`와 `clear` 후 저장 값을 재조회한다.
- HTTP 테스트는 실제 Controller, application, repository와 테스트 DB를 연결해 입력·응답·저장 결과를 검증한다.
- JUnit 테스트는 한글 `@DisplayName`, 기능별 `@Nested`, `arrange`, `act`, `assert` 구성을 사용한다.
- 오류 테스트는 예외나 상태 코드뿐 아니라 실패 후 기존 상태가 유지되는지도 확인한다.

## 좋아요 집계

- 좋아요 관계·내 목록 포함 여부는 즉시 반영하며 likeCount·인기순은 Shopping의 저장 집계 값을 사용한다.
- 단일 commerce-api 인스턴스에서 시작 시 1회, 이전 실행 종료 10초 후 전체 COUNT를 재집계한다.
- 집계 행이 없으면 0이며 마지막 취소 후에도 0으로 갱신한다. 전체 갱신 실패는 롤백·로그 후 다음 주기에 실행한다.
- 집계 스케줄러는 application 집계 Service를 호출한다. 집계 쓰기 계약은 읽기 DAO와 분리한다.
- 집계 테스트는 시간을 기다리지 않고 실행을 직접 호출한다. Redis·메시징·다중 인스턴스 조율은 추가하지 않는다.

## 검사 적용

- Checkstyle은 별표·미사용 import 금지부터 적용한다. 기본 계층 의존 검사와 신규 도메인 순수성 검사를 구분한다.
- 신규 순수성 검사는 실제 네 Context와 신규 공통 domain을 포함한다. Example은 참고 코드로 보존한다.
- 현재 존재하는 신규 클래스를 실제로 검사하며 아직 없는 Context의 순수성을 검증했다고 기록하지 않는다.
- 검사 설정·실행 상태는 사실대로 기록한다. 문서에 기준을 적었다고 설정 연결이나 검사 통과로 보고하지 않는다.

## 변경 금지

- 검사를 통과시키기 위해 테스트 기대값, 업무 규칙, Checkstyle 또는 ArchUnit 규칙을 삭제하거나 완화하지 않는다.
- 요청 범위 밖의 패키지 개편과 기존 Example 코드 리팩터링을 함께 진행하지 않는다.
- 사용자가 수정한 파일과 관련 없는 변경을 되돌리거나 덮어쓰지 않는다.
