# AGENTS.md

이 문서는 Codex가 이 저장소에서 개발 작업을 수행할 때 따라야 할 프로젝트 공통 규칙이다.

## 프로젝트 개요

- Java 21과 Spring Boot 3.4.4 기반의 Gradle 멀티 모듈 프로젝트다.
- `apps`는 실행 가능한 Spring Boot 애플리케이션이다.
- `modules`는 애플리케이션이나 특정 도메인에 종속되지 않는 재사용 가능한 기능 모듈이다.
- `supports`는 로깅, 모니터링, Jackson 설정처럼 여러 애플리케이션을 지원하는 부가 기능 모듈이다.

## 모듈 구조와 의존성 경계

- `apps:commerce-api`: HTTP API 애플리케이션
- `apps:commerce-batch`: Spring Batch 애플리케이션
- `apps:commerce-streamer`: Kafka 이벤트 소비 애플리케이션
- `modules:jpa`: JPA, Querydsl, MySQL 및 공통 영속성 설정
- `modules:redis`: Redis 연결 및 공통 설정
- `modules:kafka`: Kafka 연결 및 공통 설정
- `supports:jackson`: Jackson 보조 설정
- `supports:logging`: 로깅과 Micrometer tracing 설정
- `supports:monitoring`: Actuator와 Prometheus 설정

다음 경계를 지킨다.

- `modules`와 `supports`에서 `apps` 모듈을 의존하지 않는다.
- 특정 비즈니스 도메인에 종속된 코드는 해당 애플리케이션의 도메인 영역에 둔다.
- 공통화가 실제로 필요한 경우에만 `modules` 또는 `supports`로 이동한다.
- 기존의 `interfaces`, `application`, `domain`, `infrastructure` 계층 구성을 존중한다.
- API DTO와 영속성 엔티티를 직접 노출하지 않고 계층 경계에서 변환한다.

## 개발 원칙

- 기존 코드와 프로젝트 구조를 먼저 읽고, 요청 범위에 필요한 최소 변경만 한다.
- Java 21 문법과 현재 프로젝트의 Spring/Lombok 사용 방식을 따른다.
- 의존성 주입은 생성자 주입을 우선한다.
- 비즈니스 규칙은 서비스나 컨트롤러에 흩어지지 않도록 적절한 도메인/애플리케이션 계층에 둔다.
- 기존 `ApiResponse`, `ApiControllerAdvice` 등 공통 API 처리 방식을 재사용한다.
- 설정을 변경할 때는 `local`과 `test` 프로필에 미치는 범위를 확인한다.
- 비밀번호, 토큰, 개인 키 등 민감정보를 소스와 설정 파일에 하드코딩하지 않는다.
- 로그에 개인정보, 인증정보, 비밀번호, 토큰을 남기지 않는다.
- 요청하지 않은 대규모 리팩터링, 새 라이브러리 추가, 모듈 이동은 피한다.
- 생성물과 빌드 산출물(`build/`, 생성된 Querydsl 코드 등)은 직접 수정하지 않는다.

## 설계 기반 TDD 구현과 검증

- 구현 전에 설계 문서의 계약과 주요 규칙의 기대값을 정하고, 대표 도메인 규칙은 Red → Green → Refactor 방식으로 구현한다.
- Red 단계에서는 정상·대표 오류의 기대값을 테스트로 먼저 작성하고, 의도한 실패를 확인한다.
- Green 단계에서는 테스트를 통과시키는 최소 구현을 만들고, Refactor 단계에서는 중복·이름·책임을 다듬은 뒤 테스트를 다시 실행한다.
- 새 기능이나 버그 수정에는 변경된 동작을 검증하는 테스트를 함께 추가하거나 수정한다.
- 구현 중 계약·책임·의존에 관한 판단이 바뀌면 설계 문서와 관련 테스트를 함께 갱신한다.
- 순수 도메인 규칙은 단위 테스트로 검증한다.
- application은 요청자 구분과 객체 협력을 검증한다.
- repository·DB 테스트는 저장 뒤 `flush/clear` 후 관계와 값을 재조회해 검증한다.
- HTTP 테스트는 실제 API 응답과 입력·권한 거절 시 기존 값 유지를 검증한다.
- 계층 의존은 ArchUnit으로, Java import 규칙은 Checkstyle로 검증한다.
- 테스트 실행 시 Gradle이 사용하는 `test` 프로필과 기존 테스트 격리 방식을 유지한다.
- 기능별 테스트 후 최종 검증 명령:

  ```bash
  ./gradlew :apps:commerce-api:check
  ```

- 변경 범위를 빠르게 검증할 때는 대상 테스트를 먼저 실행한다.

  ```bash
  ./gradlew :apps:commerce-api:test --tests '*Stock*Test'
  ./gradlew :apps:commerce-api:test --tests '*ArchitectureTest'
  ```

- 테스트나 빌드가 실패하면 원인을 숨기지 말고, 실패 명령과 환경적 제약을 최종 요약에 기록한다.

## 작업 절차

1. 요청과 관련된 모듈, 설정, 테스트를 먼저 확인한다.
2. 변경 전에 현재 동작과 의존성 경계를 파악한다.
3. 작은 단위로 구현하고 관련 테스트를 실행한다.
4. 필요하면 전체 관련 모듈 테스트와 빌드를 추가로 실행한다.
5. `git diff`로 의도하지 않은 변경이 없는지 확인한다.
6. 최종 보고에는 변경 내용, 실행한 검증, 남은 이슈를 간단히 정리한다.

## AI 협업 규칙

- 합의한 계약·기대값·패키지 의존을 따른다. 미정 정책은 먼저 질문한다.
- 이번 기능에서 변경할 책임·파일·관련 테스트를 먼저 제안한다.
- 작은 기능을 구현하고 diff와 관련 테스트·lint·ArchUnit 결과를 확인한다.
- 검사를 통과시키기 위한 테스트·기대값·규칙 삭제나 완화는 하지 않는다.
- 정책·검사 기준 변경이나 범위 밖 개편은 이유와 영향을 설명하고 확인을 받는다.

## Git 및 PR 규칙

- 과제 작업 브랜치는 원본 저장소의 개인 제출 브랜치(`upstream/taegyun1995`) 최신 커밋에서 생성한다. 이름은 `feat/<작업>` 또는 `fix/<작업>`으로 작성한다.

  ```bash
  git fetch upstream
  git switch -c feat/volume-2-commerce-core upstream/taegyun1995
  ```

- 커밋은 하나의 논리적 변경 단위로 작성하고, 메시지는 `type: 작업 내용` 형식을 따른다. 예: `feat: 주문 생성 기능 구현`, `refactor: 장바구니 엔티티 리팩토링`, `test: 주문 생성 테스트 코드 추가`
- 원격 브랜치에 push한 뒤 PR을 생성한다. PR 제목은 `[volume-n] 작업 내용 요약` 형식을 따르고, PR 템플릿의 `💬 리뷰 포인트`를 3개 이내로 반드시 작성한다.
- PR 제출 전 테스트 포함, 불필요한 코드·디버깅 로그 제거, 포맷팅 및 컨벤션 준수, 필요한 문서 보강 여부를 확인한다.
- 사용자 요청 없이 커밋, 브랜치 생성, push, PR 생성, merge를 수행하지 않으며, 기존 변경사항과 무관한 파일은 건드리지 않는다.
