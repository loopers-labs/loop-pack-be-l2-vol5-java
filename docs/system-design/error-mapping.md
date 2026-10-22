# 도메인 오류와 HTTP 오류 변환

Commerce 도메인 오류를 표현하고 API 응답으로 변환하는 현재 방식을 정리한다. 엔드포인트별 성공·오류 계약은 [API 계약](api-contracts.md)을 참고한다.

## 현재 방식

- Commerce Domain은 업무 규칙 위반을 `CoreException`과 `ErrorType`으로 표현한다.
- `ErrorType`은 HTTP 상태, 오류 코드와 기본 메시지를 보유한다. 따라서 Domain은 오류 표현을 통해 HTTP 상태에 결합되어 있다.
- interfaces의 `ApiControllerAdvice`가 `CoreException`을 받아 HTTP 상태와 `ApiResponse` 오류 응답으로 변환한다.

## 브랜드 이름의 DB 충돌

사전 중복 조회를 통과한 동시 등록·수정도 DB의 `uk_brand_name` 유일 제약으로 보호한다. Infrastructure에서 MySQL의 중복 키 오류(`1062`, SQL state `23000`)와 정확한 제약 이름을 확인한 경우에만 기존 중복 이름 `CoreException(CONFLICT)`로 변환한다.

브랜드 저장은 flush까지 저장소 경계에서 실행해 충돌을 변환하고, 예외를 Facade 트랜잭션 밖으로 전달해 해당 요청을 rollback한다. 응답은 기존 `409`다. 다른 유일 제약, 잠금 시간 초과·교착 등 기술 오류는 원래 예외를 전달한다.

## 좋아요 동시 중복 등록

같은 `(userId, productId)`의 등록 경쟁은 `LikeRepository.insertIfAbsent`로 처리한다. Infrastructure는 JPA와 같은 DataSource의 현재 트랜잭션에 참여하는 `JdbcTemplate` INSERT를 사용하고, `uk_like_user_product`의 정확한 중복 키 오류만 기존 관계로 판정해 `false`를 반환한다. 다른 저장 오류는 호출자에게 전달한다.

Application은 새 관계를 저장한 경우에만 커밋 후 캐시를 무효화한다. 기존 관계와 동시 중복은 모두 좋아요 상태를 `200`으로 반환한다. 중복을 확인한 뒤 이전 읽기 스냅샷으로 관계를 재조회하지 않고, 검증한 사용자·상품 식별값으로 결과를 만든다.

이 경로는 JPA 저장 예외로 트랜잭션이 rollback-only가 되는 것을 피하면서 기존 유스케이스 트랜잭션을 유지한다. 실제 MySQL 테스트로 중복 처리 후 다른 변경의 커밋과 전체 rollback 시 관계·캐시 보존을 확인한다. JDBC INSERT의 생성·수정 시각은 UTC로 기록한다.

## 선택 이유와 감수한 점

기존 `CoreException`, `ErrorType`, `ApiControllerAdvice`의 공통 처리 방식을 유지한다. 현재 API의 `400`, `404`, `409` 계약을 그대로 쓰면서 새 기능을 구현할 수 있고, 기본 기능 범위에서 Domain 전용 예외·코드 체계와 HTTP 매핑 계층을 추가하는 비용을 줄인다.

그 결과 Domain이 HTTP 상태를 가진 `ErrorType`에 의존한다. 도메인 오류와 HTTP 표현을 분리해야 하는 요구가 생기면 Domain 전용 예외와 오류 코드를 두고, interfaces에서 HTTP 상태와 `ApiResponse`로 변환하는 방식으로 전환한다.
