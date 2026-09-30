# 패키지 구조 리팩토링 결과

[계획](plan.md) · [체크리스트](checklist.md) · [결정 기록](context-notes.md) · [테스트 경량화 결과](test-slimming.md)

## 최종 구조

domain·application·interfaces는 `layer.<ctx>.<kind>`, infrastructure만 `infrastructure.<kind>.<ctx>[.<subkind>]`다.
`find apps/commerce-api/src/main/java/com/loopers -type d`로 확인한 결과다(손대지 않은 `example`·`shared`·`support`·
`application.common` 등은 생략).

```
domain
├── mall.{model,repository}
├── ordering.{model,policy,repository}
├── pay.{model,repository}
└── shopping.{model,repository}

application
├── mall.{command,query,result,service,usecase}
├── ordering.{command,dao,query,result,service,usecase}
├── pay.{command,query,result,service,usecase}
└── shopping.{dao,query,service,usecase}

interfaces.api
├── mall.{controller,dto}
├── ordering.{controller,dto}
├── pay.{controller,dto}
└── shopping.controller

infrastructure
├── dao.{ordering,shopping}
├── initializer.shopping
├── persistence.mall.{entity,jpa,repository}
├── persistence.ordering.{entity,jpa,repository}
├── persistence.pay.{entity,jpa,repository}
├── persistence.shopping.{entity,jpa,repository}
├── query.{mall,ordering,pay,shopping}
└── scheduler.shopping
```

`domain.ordering.model`에는 `Order`가 `OrderRecord`를 1:1 자식으로 들고 있고(추가 작업 2), `OrderRecordRepository`는
없다(독립 저장소를 없앴다). 테스트 패키지는 계획대로 대상 클래스와 같은 위치로 옮겼다.

## 커밋별 요약

| 커밋 | 제목 | 내용 |
|---|---|---|
| `f2506e4` | docs: 패키지 구조 리팩토링 계획과 결정 사항 정리 | plan.md·checklist.md·context-notes.md 초안 작성 |
| `feed666` | refactor: domain 패키지를 컨텍스트·종류 구조로 재배치 | domain을 `<ctx>.{model,repository,policy}`로 이동, 참조 import 갱신 |
| `7054515` | refactor: infrastructure 패키지를 종류·컨텍스트 구조로 재배치 | infrastructure를 `{persistence,query,dao,scheduler,initializer}.<ctx>`로 이동, `UserJpaRepository`만 public화 |
| `bebe60e` | refactor: application 패키지를 컨텍스트·종류 구조로 재배치하고 조회 모델명을 View로 통일 | application을 `<ctx>.{usecase,service,command,result,query,dao}`로 이동, 조회 모델 7개를 `*View`로 개명 |
| `a9abf0d` | refactor: interfaces 패키지를 컨텍스트·종류 구조로 재배치 | interfaces.api를 `<ctx>.{controller,dto}`로 이동 |
| `bd016c5` | docs: OrderBill을 ordering의 OrderRecord로 옮기는 트레이드오프 정리 | R02 트레이드오프 10 작성, plan·checklist·context-notes 갱신 |
| `f1daf84` | refactor: OrderBill을 ordering 컨텍스트의 OrderRecord로 이관 | OrderBill/OrderBillStatus/Repository를 ordering의 OrderRecord로 개명·이동, 테이블 `order_records`로 변경 |
| `33d6584` | refactor: 주문 확정을 결제 단계와 주문 기록 단계로 나누고 Order.confirm이 OrderRecord를 반환 | 확정 흐름을 검증→결제 단계→주문 단계로 재구성, `Order.confirm()`이 `OrderRecord` 반환 |
| `9e7b2f0` | docs: OrderRecord를 Order 애그리거트에 포함하는 트레이드오프 정리 | R02 트레이드오프 11 작성, plan·checklist·context-notes 갱신 |
| `be9cf1d` | refactor: OrderRecord를 Order 애그리거트의 1:1 자식 엔티티로 편입 | `OrderRecord`에서 `orderId` 제거, `Order`가 record를 보유, `confirm()` void화, JPA `@OneToOne` cascade, 독립 저장소 삭제 |
| `cbd00d8` | docs: 레이어별 테스트 구성과 중복 현황 정리 | 테스트 경량화 조사와 결정 정리(`docs/test/`, test-slimming.md 초안) |
| `7c7a25b` | build: commerce-api 테스트를 slow·example 태그로 나누고 Redis 테스트 컨테이너를 제거 | JUnit 태그·Gradle 태스크(`test`/`slowTest`/`check`) 분리, Redis 테스트 의존 제거 |
| `3564cd4` | test: DB 정리에서 빈 테이블을 건너뛰고 MySQL 테스트 컨테이너 재사용을 허용 | `DatabaseCleanUp` 최적화, `withReuse(true)` 추가 |
| `371801e` | test: 주문·지갑 테스트에서 다른 층과 겹치는 검증을 정리 | ordering·pay 관련 중복 테스트 삭제/축소 |
| `8a4fd87` | test: 브랜드·좋아요 테스트에서 다른 층과 겹치는 검증을 정리 | mall·shopping 관련 중복 테스트 삭제/축소 |
| `57d1436` | test: 공용 테스트 어노테이션으로 Spring 컨텍스트를 통합하고 무거운 테스트에 태그 부착 | `@IntegrationTest`/`@E2ETest` 도입으로 컨텍스트 6→2, 나머지 태그 부착 마무리 |
| `6ddb3e7` | docs: 테스트 경량화 결과와 레이어별 테스트 문서 갱신 | test-slimming.md·`docs/test/` 최종화 |

(세 건의 `Merge branch 'worktree-agent-...' into volume-3/refacto-test-slim` 병합 커밋은 병렬 작업 스트림을
합친 것으로, 위 목록에는 포함하지 않았다.)

## 계획과 달라진 점

- 종류(kind) 폴더로 파일이 갈라지면서 같은 feature 안에서 암묵적으로 쓰던 타입들에 명시적 `import`가 대거 늘었다
  (도메인 4건, infrastructure의 entity/jpa/repository 상호 참조, application의 usecase/service/command/result/query/dao
  상호 참조, interfaces의 controller→dto 참조 등). 자세한 내역은 context-notes.md의 "작업 중 기록"에 있다.
- QueryDSL이 생성하는 `Q*JpaEntity`도 원본 엔티티와 같은 새 패키지 경로로 생성되어, `QueryDslProductQueryDao`의
  `Q` 클래스 import 경로를 함께 갱신해야 했다.
- 접근제어 확대는 계획대로 `UserJpaRepository` → `public` 한 건만 발생했다.
- 조회 모델 7개(`BrandDetail`→`BrandView` 등)를 계획대로 `*View`로 통일했다.
- 커밋 4(interfaces)는 사용자 요청으로 ArchUnit·전체 테스트를 생략하고 컴파일 + Checkstyle만 먼저 확인했다.
  ArchUnit 회귀는 계획대로 뒤로 미뤄, 테스트 경량화 이후의 전체 `check` 실행으로 확인했다.
- 애초 계획에는 없던 두 단계 추가 결정이 들어갔다: 먼저 `OrderBill`을 pay에서 ordering의 `OrderRecord`로 옮기고
  (트레이드오프 10), 이어서 `OrderRecord`를 `Order` 애그리거트의 1:1 자식으로 편입했다(트레이드오프 11).
  두 번째 결정으로 `Order.confirm()`은 `OrderRecord`를 반환하다가 다시 `void`가 됐고, `OrderRecordRepository`는
  생겼다가 없어졌다.
- `OrderRepositoryImpl.save`는 `saveAndFlush`로 바뀌었다 — `assignRecord`로 매단 자식 엔티티의 id·`createdAt`이
  `mapper.toDomain` 호출 시점에 항상 채워져 있도록 보장하기 위한 최소 변경이다.
- Hibernate가 `mappedBy` 쪽 `@OneToOne`을 지연 로딩하지 못해, 주문을 조회할 때마다 기록 조회 SELECT가 하나 더
  나간다(트레이드오프 11에서 감수하기로 한 비용).
- `OrderRecordRepositoryIntegrationTest`의 "주문당 기록 하나" 검증은 유일성을 실제로 검증하지 못해 삭제하고,
  `OrderRepositoryIntegrationTest`로 검증 방식을 바꿔 이관했다.
- "결제 기록"이라는 표현을 "주문 기록"으로 맞추는 용어 정리는 A2·A3 커밋에서 변수명·일부 주석까지 했고,
  B2에서 새로 생긴 예외 메시지·주석과 테스트 표시 이름은 후속 커밋 `39a456a`에서 통일했다(동작 변경 없음).

## 검증

- 레이어별 커밋(0~4)은 매 커밋 컴파일 + Checkstyle + ArchUnit(커밋 4는 컴파일 + Checkstyle만, 사유는 위 참고)을
  통과한 뒤 커밋했다.
- 추가 작업 A1~A3, B1~B2는 매 커밋 지정된 범위(ordering·pay 관련 도메인/애플리케이션/인프라/인터페이스, ArchUnit)의
  테스트를 통과한 뒤 커밋했다. B2 시점 지정 범위 테스트는 112건 전부 통과했다.
- 테스트 경량화 이전 R02 최종 `check`는 247건이었다.
- 테스트 경량화 이후 전체 `./gradlew :apps:commerce-api:check`는 **BUILD SUCCESSFUL, 4분 33초**로 통과했다.
  Spring 컨텍스트는 2개(`test`·`slowTest` 각 JVM에서 MOCK 1 + RANDOM_PORT 1)로 줄었고, 테스트 수는
  `test` 211건 + `slowTest` 17건 = 228건, 실패 0건이었다. 이 실행이 커밋 4에서 미뤄뒀던 ArchUnit·전체 테스트
  회귀 확인을 겸했다.

## 남은 과제

- 브랜드·상품·좋아요 application 서비스에는 단위 테스트가 없어 해당 분기는 E2E 테스트만 검증하고 있다.
- `QueryDslProductQueryDao`(상품 정렬·페이징 SQL)는 통합 테스트가 없어 `ProductApiE2ETest`만 검증하고 있다.
- `OrderRecordStatus`의 값 이름 `PAID`는 "결제 상태"를 뜻하던 시절의 이름이 남은 것으로, 지금은 "주문이 확정됐다는
  사실"을 표현하는 상태값이라 이름이 다소 어색하다(트레이드오프 10에서 감수하기로 한 지점).
- 이 리팩토링·추가 작업·테스트 경량화 전체에 대한 PR은 아직 제출하지 않았다.
