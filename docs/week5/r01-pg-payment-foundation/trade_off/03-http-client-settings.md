# HTTP 호출 방식·시간 제한

[설계 질문 목록](total_trade_off.md) · [요구사항 G06](../requirement.md) · [응답 해석·검증](04-adapter-fixture.md)

상태: **A·전송 설정 확정·구현 전**. 2026-10-21 사용자가 03과 04의 추천안을 함께 선택했다. 학습에 필요한 범위로 제한한다.

## 판단할 문제

현재 commerce는 Spring MVC이며 PG HTTP client 구현은 없다. PG 생성·거래 조회·주문별 조회 세 경로를 호출해야 한다.
호출 코드의 형태를 고르고 실제 연결/응답 대기를 제한한다. create 자동 재시도 금지는 이미 확정된 요구사항이며 선택지가 아니다.

## 호출 코드 비교

| 대안 | 동작 | 이득 | 비용 |
|---|---|---|---|
| A. RestClient로 세 경로를 직접 호출 · 채택 | adapter에서 메서드·URI·본문·응답 변환을 명시 | 호출 흐름과 오류 해석을 코드에서 바로 확인 가능. 현재 MVC 구조에 맞는 동기 호출 | 경로별 호출 코드를 직접 작성 |
| B. Spring HTTP Interface 프록시 · 미채택 | 인터페이스의 어노테이션으로 경로를 선언하고 호출 구현 생성 | 비슷한 API가 많아지면 선언 중복을 줄임 | 프록시 구성과 별도 선언을 따라가야 함. 이번 세 API에는 이득이 작음 |

학습용 세 경로의 호출·오류 흐름을 직접 확인하기 위해 A를 선택했다. 비동기/리액티브 구조 전환, 여러 PG 제공자용 공통 프레임워크는 이번 범위에 포함하지 않는다.
RestClient의 동기 호출과 HTTP Interface는 [Spring 6.2 공식 문서](https://docs.spring.io/spring-framework/reference/6.2/integration/rest-clients.html)를 근거로 비교했다.

## 확정한 최소 전송 설정

- RestClient 아래 전송 구현은 Apache HttpClient 5와 HttpComponentsClientHttpRequestFactory를 명시한다. client 자체의 자동 재시도와 자동 redirect를 끈다. 조회 재시도도 client가 몰래 수행하지 않고 R03의 정해진 회차에서만 수행한다.
- Apache 의존성 하나가 추가되지만 자동 재전송 차단을 명시할 수 있다. 별도 프록시 서버·네트워크 장애 도구·서비스 메시를 구성하지 않는다. 라이브러리 버전은 현재 Boot 의존성 관리와 호환되는 것으로 구현 시 확인하며 업그레이드를 전제하지 않는다.
- 학습용 초기값은 연결 1초·연결 풀에서 연결을 얻는 대기 1초·응답 대기 2초다. 생성/조회에 동일하게 적용하고 설정값으로 바꿀 수 있게 한다.
- 위 시간은 각각의 하위 대기 제한이다. 세 값이나 2초를 호출 전체의 절대 제한으로 설명하지 않는다. 연결 이후 본문 읽기 정지에도 무한 대기가 남지 않도록 실제 client 설정과 테스트로 확인한다.
- timeout이면 PG 결과 미확인이다. 생성은 재발송하지 않고 기존 거래 조회 경로로 넘긴다. 재조회 횟수·1/3/10초와 지터 ±20% 결정은 유지한다.
- 로컬 기본 실험은 PG 처리 3초·생성 응답 지연 4초처럼 응답 제한보다 긴 조건으로 구성할 수 있다. 이 값은 프리셋 확정값이 아닌 설명용 예다. PG 처리는 commerce timeout 뒤에도 계속된다.
- 설정 검증은 필수 주소·양수 timeout·기존 로컬 주소 정책 정도로 제한한다. 요청/응답 본문 전체를 로그로 남기지 않는다.

전송 설정의 근거는 [Spring 6.2.5 request factory 문서](https://docs.spring.io/spring-framework/docs/6.2.5/javadoc-api/org/springframework/http/client/HttpComponentsClientHttpRequestFactory.html)와 [Apache HttpClient 5.4.2 구현](https://github.com/apache/httpcomponents-client/blob/rel/v5.4.2/httpclient5/src/main/java/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.java)이다. 참조 버전을 이번 의존성 버전으로 확정한 것은 아니다.

## 검증할 최소 범위

- 실제 HTTP 요청의 메서드·경로·고정 제공자 헤더·URI 인코딩·JSON 타입과 금액이 계약에 맞는지 확인한다.
- 생성 응답 지연/연결 종료 뒤 추가 POST가 생기지 않고, PG에 생성된 거래는 별도로 조회할 수 있는지 확인한다.
- 조회 응답 지연/본문 읽기 정지에서 대기가 제한되고 하위 client가 추가 GET을 수행하지 않는지 확인한다.
- redirect 응답을 따라 추가 요청하지 않는지, 잘못된 설정이 기동 단계에서 발견되는지 확인한다.

테스트 PG와 테스트용 HTTP 서버의 역할은 [04](04-adapter-fixture.md)에 정리한다. 성능 튜닝·운영용 전체 deadline/분산 추적 설계는 이번 선택에 추가하지 않는다. R03의 회로/실행 예산과 필요한 정합성 확인은 남는다.
현재는 설계 결정 문서이며 client·timeout·테스트 구현 완료를 의미하지 않는다. 구체적인 의존성 버전·설정 키·클래스명·본문 읽기 제한의 실제 적용 지점은 구현 전 상세 설계에서 확인한다.
