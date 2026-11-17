# 현재 개발 컨벤션

2주차에 시작한 관례를 현재 구현과 대조해 유지하는 문서다. 2주차의 당시 API·POINT 결제·순차 처리 계약은
[주차 안내](README.md)의 학습 기록이며, 현재 계약과 구분한다. 협업·권한·사용자 변경 보존은 [AGENTS](../../AGENTS.md)를 따른다.

## 구조와 의존

| 위치 | 형태와 책임 |
|---|---|
| domain | `domain.<context>.{model,repository,policy}`: 순수 Java 상태·행동·저장 계약 |
| application | `application.<context>.{usecase,service,facade,command,result,query,dao,gateway,...}`: 업무 순서·TX·외부 계약 |
| interfaces | `interfaces.api.<context>.{controller,dto}`: 입력·응답·HTTP 오류 매핑 |
| infrastructure | `infrastructure.<kind>.<context>`: persistence/query/dao/scheduler/gateway 등 기술 구현 |

업무 Context는 mall/shopping/ordering/pay/coupon이다. shared는 공통 값·오류, example은 보존하는 참고 코드다.
도메인은 다른 계층·Spring·JPA·HTTP에 의존하지 않고 저장 Entity와 분리한다. application은 interfaces/infrastructure에,
interfaces는 infrastructure에 의존하지 않는다. infrastructure는 application의 DAO/gateway 계약·조회 record를 구현/사용할 수 있으나
Service 구현이나 interfaces에 의존하지 않는다. 실제 강제 범위는 [계층 검사](../../apps/commerce-api/src/test/java/com/loopers/architecture/LayerArchitectureTest.java)와
[순수성 검사](../../apps/commerce-api/src/test/java/com/loopers/architecture/DomainPurityArchitectureTest.java)를 확인한다.
패키지 변경의 이유와 당시 검증은 [리팩터링 결과](../refactor/result.md), [결정 기록](../refactor/context-notes.md)에 있다.

## 모델·변환·데이터 접근

- 숨긴 생성자와 create/restore를 구분한다. 공개 setter 대신 의미 있는 행동으로 상태를 바꾸고 실패 후 메모리 값도 보존한다.
- Money·Stock 같은 값 객체는 자기 범위를 검사하고 행동별 입력·잔액·재고 조건은 해당 업무에서 확인한다.
  합산·곱셈 overflow, 중복 품목, 삭제 상태, 저장 snapshot을 보존한다.
- 도메인 변경 후 repository.save를 명시한다. 분리 도메인은 JPA dirty checking 대상이 아니다.
  RepositoryImpl은 기존 Entity를 확보하고 EntityMapper는 ID·시각·품목·snapshot을 보존한다. Mapper는 Entity와 같은 entity 패키지에 두며 업무 판단을 넣지 않는다.
- 쓰기는 Request → Command → Result → 응답, 조회는 Request → Criteria → View → ApiResponse를 기본으로 한다.
  현재 주문처럼 View 변환을 재사용하는 실제 계약을 존중하며 형식을 맞추려고 응답을 바꾸지 않는다.
- 일반 GET은 QueryController → application QueryDao → infrastructure QueryDSL projection이다.
  DAO의 상세 Optional 없음은 Controller가 HTTP 오류로 변환하며 DAO에 HTTP 정책을 넣지 않는다.
- 운영 JDBC 의존은 Jdbc* 기술 adapter에 제한하는 ArchUnit 규칙이 있다. 좋아요 집계·쿠폰 실행 증거 같은 기술 경계를 구분한다.

## 트랜잭션·사용자·현재 기능 경계

쓰기 TX는 업무 단위의 공개 서비스 경계, 조회 readOnly TX는 DAO 구현에 둔다. 외부 호출까지 하나의 TX로 묶지 않는다.
현재 [ConfirmOrderFacade](../../apps/commerce-api/src/main/java/com/loopers/application/ordering/facade/ConfirmOrderFacade.java)는
TX 밖 실행을 강제하며 준비 → PG 호출 → 관찰 저장 → 결과 적용 → 필요 시 자동 회복으로 조율한다.
과거 ConfirmOrderWriter와 POINT 단일 TX는 당시 구현 기록이다. [결제 상태](../week5/states/total.md)와 [회복 전략](../week5/resilience/README.md)을 따른다.

`@XUserId`는 헤더 형식과 UserQueryDao의 존재를 확인한다. 형식 오류는 400, 없는 fixture 사용자는 404이며 실제 인증이 아니다.
현재 주문 생성·상세·확정·대사는 고객 식별과 소유권을 확인한다. 관리자 fixture 접근은 해당 관리자 경로에서 확인한다.
과거의 헤더/소유권 제외 정책으로 현재 응답을 변경하지 않는다. 같은 요청의 결과·권한·부수 효과 순서는 기능별 설계를 따른다.

좋아요는 실제 관계 변경 때만 commit 후 증감 이벤트를 모아 5초 fixed delay로 products.like_count에 반영한다.
실패한 delta는 복구하고 시작 시 전체 재집계한다. 관계 조회는 즉시, 표시 집계는 지연될 수 있다.
상품 목록 개수 캐시와 상세 Caffeine 캐시의 범위·기본값·무효화는 [캐시 요구사항](../week4/r02-product-detail-local-cache/requirement.md)을 따른다.
Outbox·Inbox의 전달 경계는 [이벤트 계약](../week6-7/r01-order-events-inbox/event-contract.md), 쿠폰은 [현행 설계](../week8/design.md)를 따른다.

## 오류와 검증

Request는 형식·구조, domain은 값·상태·업무 불변식, application은 대상·여러 객체·흐름 조건을 검사한다.
구조 복원 오류에는 IllegalArgumentException, 외부 업무 코드가 필요한 경우 DomainException/DomainErrorCode를 사용한다.
ApplicationException/ApplicationErrorCode와 legacy CoreException/ErrorType도 실제 공통 API 오류 매핑을 따른다.
오류 문구만 바꾸어도 안정적인 코드·HTTP 계약·실패 후 상태가 보존되어야 한다. 개별 오류의 상태 코드는 실제 mapper와 기능 계약을 확인한다.

검사는 [domain](../test/domain.md), [application](../test/application.md), [infrastructure](../test/infrastructure.md),
[interfaces](../test/interfaces.md)의 책임별로 선택한다. 도메인 경계는 Spring 없이, 저장·동시성·DDL은 실제 DB에서,
외부 기술과 HTTP는 해당 통합 경계에서 확인한다. 준비·컴파일·mock 성공을 실제 장애 재현 성공으로 표현하지 않는다.

## 스타일과 명세·구현의 차이

Java 21, 생성자 주입, 기존 Lombok·JUnit·AssertJ 관례를 따른다. 별표·미사용 import를 금지하고
`.editorconfig`의 본문 130자와 테스트 예외, 실제 Checkstyle·ArchUnit 설정을 유지한다.
R08은 테스트 JDBC 제거를 선택했으나 현재 쿠폰 수용/집계 시험은 JdbcTemplate을 사용한다.
ArchUnit 운영 규칙은 테스트를 제외한다. 이는 현재 관찰한 차이이며 문서 정리로 테스트 전반의 예외를 승인하거나 검사 규칙을 완화한 것이 아니다.
테스트 총수·측정 시간은 실행 revision·환경과 함께 결과 기록에 남기며 이 문서의 고정 기준으로 삼지 않는다.
