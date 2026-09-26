# 아키텍처

## 목표

시스템이 지켜야 하는 불변식을 지킨다.

### 불변식

불변식은 시스템이 언제 보아도 참이어야 하는 조건이다. 어떤 요청을 처리한 뒤에도 깨지지 않는다.

**원칙**

- 불변식은 요구사항에서 도출된다.
- 모든 불변식에는 출처가 되는 요구사항이 있다.

## 각 계층의 역할


| 계층             | 역할                                                                     |
| -------------- | ---------------------------------------------------------------------- |
| domain         | 불변식을 가둔다.                                                              |
| application    | 도메인의 판단을 모아 그 시나리오의 성공·실패와 결과를 내보낸다.                                   |
| interfaces     | 요청자와의 요청·응답 계약을 정의하고 지킨다. 요청을 계약대로 받아 application에 넘기고, 결과를 계약대로 응답한다. |
| infrastructure | 외부 기술과 연결하는 구현을 담는다.                                                   |


## HTTP DTO

- 요청·응답 DTO는 기능별 `interfaces` 패키지의 `*V1Dto.java`에 둔다. 현재 `BrandV1Dto`, `ProductV1Dto`, `PointV1Dto`, `OrderV1Dto`가 있다.
- 요청 DTO 이름은 `Request`, 응답 DTO 이름은 `Response`로 끝난다. 공통 응답 형식은 `interfaces/api`의 `ApiResponse`, `ListResponse`에 둔다.
- controller는 요청 DTO를 application 입력으로 바꾸고, application 결과를 응답 DTO로 바꾼다. HTTP DTO를 domain이나 infrastructure에 전달하지 않는다.

## 의존 방향

- 불변식 로직은 외부 기술을 모른다. 그래서 외부 기술을 생각하지 않고 불변식을 다룰 수 있고, 혹여나 외부 기술이 바뀌어도 불변식 로직은 바뀌지 않는다.
- 각 계층의 역할에 따라 의존 방향은 아래와 같다.

```text
interfaces ──▶ application ──▶ domain ◀── infrastructure
```


| 계층          | 의존할 수 없는 계층                             |
| ----------- | --------------------------------------- |
| domain      | interfaces, application, infrastructure |
| application | interfaces, infrastructure              |
| interfaces  | infrastructure                          |


## JPA 영속성 모델

- 현재 `Brand`, `Product`, `User`, `Order`, `Like`는 도메인 엔티티이자 JPA Entity인 동일 클래스다.
- domain에는 JPA 매핑 어노테이션과 기본 생성자가 있지만, 불변식 판단 로직은 JPA 저장소·쿼리·EntityManager에 의존하지 않는다.
- domain에 Repository 포트를 두고 infrastructure의 JPA 어댑터가 구현한다. 선택 이유, 비용, 재검토 조건은 [ADR-006](./decisions.md#adr-006-repository에는-dip를-적용하고-domain-객체를-jpa-entity로-사용한다)에 기록한다.
