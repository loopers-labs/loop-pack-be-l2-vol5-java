# 영속성 매핑 설계

Commerce 도메인 모델과 JPA 저장 모델의 경계를 정리한다. 주문 확정의 트랜잭션·조건부 갱신 동작은 [주문 확정 설계](use-cases/order-confirmation.md)를 참고한다.

## 매핑 경계

| 관심사 | Domain | Infrastructure |
| --- | --- | --- |
| 상품과 브랜드 관계 | `Product`가 `brandId` 보유 | `ProductJpaEntity`가 `BrandJpaEntity`와 외래 키 연관관계 보유 |
| 주문 품목 | `Order` 내부의 식별자 없는 `OrderItem` 값 | `OrderItemJpaEntity`가 테이블 행의 DB 식별자 보유 |
| 영속성 매핑 | Commerce 도메인 객체는 JPA 어노테이션과 공통 JPA `BaseEntity`에 의존하지 않음 | JPA 엔티티가 테이블·연관관계를 매핑하고, 해당 엔티티의 공통 생성·수정·삭제 필드에는 `BaseEntity` 사용 |
| 객체 변환 | Repository 인터페이스로 조회·저장 계약 제공 | Repository 구현체와 Mapper가 도메인 객체와 JPA 엔티티 변환 |

## 선택 이유와 영향

Commerce 도메인 모델과 JPA 엔티티를 분리해 업무 규칙이 JPA 프록시와 영속성 컨텍스트의 생명주기에 묶이지 않게 한다. `Order`는 도메인에서 `OrderItem`을 내부 값으로 보유하고, 저장 관계와 행 식별자는 Infrastructure의 JPA 엔티티에 둔다.

이 구조에서는 매퍼가 필요하고, 도메인 객체의 변경을 Repository 저장·갱신 계약으로 명시적으로 반영해야 한다. 주문 확정처럼 동시성 제어가 필요한 변경은 유스케이스에 맞는 조건부 갱신 계약을 사용한다.

좋아요의 `insertIfAbsent`는 같은 DataSource·트랜잭션의 JDBC INSERT로 관계를 추가한다. 기존 좋아요의 조회·일반 저장·삭제는 JPA 매핑을 유지한다. 동시 중복의 판정과 트랜잭션 처리 이유는 [오류 변환 설계](error-mapping.md#좋아요-동시-중복-등록)에 정리한다.

이 분리는 Commerce 도메인에 적용한다. Example starter 모델은 기존 JPA 구성을 유지하며, Commerce 테이블·컬럼·제약은 변경하지 않는다.
