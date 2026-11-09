# Redis 설정 정리 — 무엇을, 왜 이렇게 설정했는가

4주차에 상품 목록 조회에 Redis 캐시를 붙이면서 쓰는 Redis 설정을 한곳에 모았다. 항목마다 **값**, **목적**, **이렇게 정한 이유(근거)**를 적는다. 근거에 측정값이 있으면 실습 환경(노트북 + Docker Desktop, 같은 PC의 MySQL·Redis)에서 잰 값이다.

설정은 네 곳에 나뉘어 있다.

| 위치 | 맡는 것 |
|---|---|
| `modules/redis` (공용 모듈) | 연결 구성, 템플릿, 대기 시간 옵션을 **받을 수 있는 틀** |
| `apps/commerce-api/src/main/resources/application.yml` | commerce-api가 쓰는 **대기 시간 값** |
| `apps/commerce-api` 캐시 코드 | 키 형식, TTL, 저장 방식, 캐시할 범위 |
| `docker/*.yml`, 테스트 픽스처 | Redis 서버 자체 (로컬·실습·테스트) |

---

## 1. 연결 구성

### 1-1. master·replica 정적 구성

| 항목 | 값 | 위치 |
|---|---|---|
| 구성 방식 | `RedisStaticMasterReplicaConfiguration` (master 1대 + replica 목록) | `RedisConfig` |
| DB 번호 | `0` | `redis.yml` |
| 주소 (`local`·`test` 프로필) | master `localhost:6379`, replica `localhost:6380` | `redis.yml` |
| 주소 (`dev`·`qa`·`prd`) | 환경 변수 `REDIS_MASTER_HOST/PORT`, `REDIS_REPLICA_1_HOST/PORT` | `redis.yml` |

**목적**: 쓰기는 master, 읽기는 replica로 나눌 수 있게 한다.

**이유**: 템플릿 프로젝트의 기존 구성이다. 4주차에 바꾸지 않았다.

### 1-2. 기본 연결은 replica 우선, 별도로 master 전용 연결

| 빈 | 읽기 위치 | 용도 |
|---|---|---|
| `defaultRedisConnectionFactory` / `defaultRedisTemplate` (`@Primary`) | `REPLICA_PREFERRED` (replica가 있으면 replica, 없으면 master) | **캐시가 쓰는 연결** |
| `masterRedisConnectionFactory` / `masterRedisTemplate` (한정자 `redisTemplateMaster`) | `MASTER` | 쓰기 직후 반드시 최신 값을 읽어야 할 때 |

**목적**: 읽기 부하를 replica로 분산하고, 최신 값이 꼭 필요할 때만 master에서 읽는다.

**캐시에 기본(replica 우선) 연결을 쓴 이유**
- 캐시 키에 revision(DB에 있는 번호)이 들어가서, **한 키의 값은 한 번 저장되면 바뀌지 않는다.**
- 그래서 replica에 아직 복제되지 않은 키를 읽으면 "옛 값"이 아니라 **없음(miss)**이 나온다. miss면 DB에서 읽으므로 결과가 틀리지 않는다.
- 쓰기 명령(`SET`, 스크립트 실행)은 연결 구성이 알아서 master로 보낸다.
- 반대로 "현재 버전 번호"를 Redis에 두는 설계였다면 replica 지연 때문에 옛 번호를 읽을 수 있어서 master 연결이 필요했다. 이 설계에서는 그 번호를 DB에 두었기 때문에 필요 없다.

### 1-3. 직렬화

| 항목 | 값 |
|---|---|
| 키·값·해시 키·해시 값 | `StringRedisSerializer` (문자열 그대로) |
| 캐시 값 | 캐시 코드에서 Jackson `ObjectMapper`로 JSON 문자열을 만들어 저장 |
| Spring Data Redis 리포지토리 | `spring.data.redis.repositories.enabled: false` |

**목적**: Redis에 저장되는 형태를 사람이 읽을 수 있는 문자열·JSON으로 고정한다.

**이유**
- 기존 템플릿 설정이다. 자바 기본 직렬화(바이너리)보다 `redis-cli`로 값을 직접 확인하기 쉽고, 클래스가 바뀌어도 역직렬화가 덜 깨진다.
- JSON 변환을 캐시 코드에서 직접 하면, 값을 읽지 못하는 경우(형식이 바뀐 옛 값)를 **캐시 오류로 따로 셀 수 있다** (5-2절).

---

## 2. 대기 시간 상한 (4주차에 추가)

Redis가 느리거나 멈췄을 때 **요청이 얼마나 오래 기다릴지**를 정하는 설정이다. 기다린 뒤에는 캐시 오류로 보고 DB에서 조회한다.

### 2-1. 공용 모듈은 "선택 속성"으로만 추가

| 속성 | 지정하지 않으면 |
|---|---|
| `datasource.redis.command-timeout` | Lettuce 기본 동작 (명령 대기 60초) |
| `datasource.redis.connect-timeout` | Lettuce 기본 동작 |
| `datasource.redis.reject-commands-when-disconnected` | `false` — 연결이 끊긴 동안 명령을 쌓아 두고 재연결을 기다림 |

**목적**: commerce-api만 짧은 대기 시간을 쓰고, 같은 모듈을 쓰는 다른 앱(batch, streamer)의 동작은 바꾸지 않는다.

**이유**: 공용 모듈의 기본값을 바꾸면 이 과제와 상관없는 앱의 동작까지 바뀐다. 값을 넣은 앱에만 적용되게 했다.

### 2-2. commerce-api가 쓰는 값

```yaml
datasource:
  redis:
    command-timeout: 300ms
    connect-timeout: 1s
    reject-commands-when-disconnected: true
```

| 설정 | 값 | Lettuce에서 실제로 바뀌는 것 | 목적 |
|---|---|---|---|
| `command-timeout` | **300ms** | `TimeoutOptions` (연결된 뒤 각 명령의 만료 시간) | Redis가 응답하지 않을 때 한 명령을 기다리는 상한 |
| `connect-timeout` | **1초** | TCP 연결 대기(`SocketOptions`) + 연결 직후 초기화(핸드셰이크)에 쓰는 시간 | 연결을 새로 맺는 과정의 상한 |
| `reject-commands-when-disconnected` | **true** | `DisconnectedBehavior.REJECT_COMMANDS` | 연결이 끊긴 동안 명령을 기다리지 않고 바로 실패 |

#### 명령 상한을 300ms로 정한 이유

- 정상일 때 Redis 왕복은 p50 2.25ms, p95 3.00ms였다(같은 PC에서 `PING` 200회). 300ms는 정상 응답보다 100배 이상 길어서, 정상 상황을 오류로 잘못 판단하지 않는다.
- 캐시를 못 쓰면 DB로 조회하는데, 인덱스를 적용한 뒤 목록의 DB 경로는 수십~수백 ms다. Redis를 그보다 훨씬 오래 기다리는 것은 의미가 없다.
- 기본값(60초)이면 Redis가 멈춘 동안 요청 스레드와 DB 연결이 오래 묶인다. 캐시 조회는 읽기 트랜잭션 안에서 하기 때문에 DB 연결을 잡은 채로 기다리게 된다.

#### 연결 수립 상한을 명령 상한과 따로 1초로 둔 이유

- 처음에는 300ms 하나로 연결까지 제한했다.
- 그런데 테스트에서 **앱이 뜬 뒤 첫 요청만 캐시를 쓰지 못하고 DB로 넘어갔다.** 오류는 `Connection initialization timed out after 300 millisecond(s)`였다.
- Lettuce는 연결 직후의 초기화(역할 확인 등 핸드셰이크)에도 같은 시간 값을 쓴다. JVM에서 처음 연결할 때는 Netty 초기화와 master·replica 역할 확인이 겹쳐 300ms를 넘었다.
- 이건 배포 직후 첫 요청에도 똑같이 생기는 문제라, 연결 수립(1초)과 연결된 뒤의 명령(300ms)을 분리했다. 분리한 뒤 이 오류로 실패하던 테스트가 통과했다.

#### 끊긴 동안 바로 실패하게 한 이유

- Lettuce 기본 동작은 연결이 끊기면 명령을 쌓아 두고, 재연결되거나 시간이 다 될 때까지 기다린다. 그러면 Redis가 멈춘 동안 **모든 요청이 상한 시간만큼 기다린 뒤에야** DB로 간다.
- 바로 실패하게 하면 기다림 없이 DB로 간다. Redis를 실제로 멈추고 잰 결과, 응답 시간이 Redis 없이 DB만 쓸 때와 같았다.
- 테스트(전용 Redis 컨테이너를 실제로 멈춤)에서 Redis가 멈춘 동안의 목록 조회가 1초 안에 DB 결과를 돌려주는 것을 확인했다.

---

## 3. 캐시 키와 값

캐시는 두 종류다. **목록 캐시**는 순서(상품 id)만, **상품 캐시**는 상품 정보만 담는다.

### 3-1. 키 형식

| 캐시 | 키 형식 | 예 |
|---|---|---|
| 목록 캐시 | `product:list:v2:brand={브랜드 id 또는 all}:sort={정렬}:rev={목록 revision}:page={페이지}:size={크기}` | `product:list:v2:brand=18:sort=PRICE_ASC:rev=0:page=0:size=20` |
| 상품 캐시 | `product:detail:v1:id={상품 id}:rev={상품 revision}` | `product:detail:v1:id=105:rev=3` |

**키에 넣은 것과 이유**

| 키 구성 요소 | 넣은 이유 |
|---|---|
| `brand` | 다른 브랜드의 목록이 섞이지 않게. 브랜드를 지정하지 않은 전체 목록은 `all` |
| `sort`, `page`, `size` | 같은 키에 다른 순서·다른 구간이 저장되지 않게 |
| `rev` | **신선도를 지키는 핵심.** 목록이나 상품이 바뀌면 DB의 revision이 같은 트랜잭션에서 올라가고, 다음 조회는 새 키를 쓴다. 옛 키는 더 이상 읽히지 않는다 |
| `v1`, `v2` | 값의 저장 형식 버전. 목록 캐시가 "상품 정보 전체"에서 "상품 id 목록"으로 바뀌어 `v2`가 됐다. 배포 전후에 형식이 다른 값을 잘못 읽지 않게 한다 |
| 사용자 정보 | **넣지 않는다.** 응답에 사용자별 필드(내가 좋아요했는지 등)가 없어서 모든 사용자가 같은 키를 공유해도 된다 |

### 3-2. 값에 담는 것

| 캐시 | 담는 것 | 담지 않는 것 |
|---|---|---|
| 목록 캐시 | 정렬된 상품 id 목록, 전체 건수 | 상품 정보 |
| 상품 캐시 | 상품 id, 브랜드 id, 이름, 가격, 재고, 좋아요 수 | **브랜드 이름**, 사용자별 정보 |

**나눈 이유**
- 목록에 상품 정보까지 담으면 좋아요 하나, 주문 확정(재고) 하나만으로도 그 브랜드의 목록 캐시가 모두 무효화된다.
- 나누면 순서를 바꾸지 않는 변경(재고, 이름, 주문 확정)은 목록 캐시를 그대로 쓰고 **바뀐 상품만** 다시 읽는다. 테스트에서 재고를 바꾼 뒤 목록 조회를 하지 않고 그 상품 1개만 DB에서 읽는 것을 확인했다.

**브랜드 이름을 담지 않은 이유**: 브랜드 이름을 바꾸면 그 브랜드의 상품 수천 개 캐시를 모두 버려야 한다. 대신 조회할 때마다 브랜드를 기본 키로 읽어 붙인다(목록당 1회, 저렴함).

### 3-3. 캐시하는 범위

| 항목 | 값 | 이유 |
|---|---|---|
| 목록 캐시 페이지 | **0~4페이지 (앞 5페이지)** | 상품이 많은 브랜드는 100페이지가 넘는다. 가격이 바뀌거나 상품이 추가·삭제되면 순서가 밀려 여러 페이지가 한꺼번에 바뀐다. 재사용이 많은 앞 페이지만 캐시해서 버려지는 키를 줄인다. 5페이지부터는 DB에서 읽는다 |
| 캐시하는 API | 고객 상품 목록만 | 측정상 비싼 경로가 목록이었다. 상세·관리자 조회·내 좋아요 목록·주문은 캐시하지 않는다 |
| 주문 | **캐시를 쓰지 않는다** | 주문 확정의 금액·재고·포인트 판단은 항상 DB 규칙대로 한다. Redis 장애와도 무관하다 |

---

## 4. TTL

| 캐시 | TTL |
|---|---|
| 목록 캐시 | 10분 |
| 상품 캐시 | 10분 |

**목적**: **옛 revision 키를 정리하는 수단**이다. 신선도를 지키는 수단이 아니다.

**이유**
- revision이 바뀌면 옛 키는 더 이상 읽히지 않지만 Redis에는 남는다. TTL이 없으면 쓸모없는 키가 계속 쌓인다.
- 최신성은 revision이 보장하므로, TTL을 짧게 잡아 신선도를 맞출 필요가 없다. 그래서 히트율을 해치지 않을 만큼 넉넉하게(10분) 뒀다.
- 과제가 경계한 "TTL만으로 변경 직후의 최신성을 주장하는 설계"가 아니다. TTL이 없어도 변경 뒤 조회는 최신이다.
- 읽을 때 TTL을 연장하지 않는다(만료 시간 고정). 자주 읽히는 옛 값이 계속 살아남는 일을 막는다.

---

## 5. 읽기·쓰기 방식

### 5-1. 여러 상품 읽기: `MGET` 한 번

목록 하나(20개)의 상품 캐시를 `MGET` 한 번으로 읽는다. 상품마다 `GET`을 보내면 Redis 왕복이 20번 생긴다.

### 5-2. 여러 상품 저장: Lua 스크립트 한 번

```lua
-- KEYS: 상품 캐시 키들, ARGV[1]: TTL(ms), ARGV[2..]: KEYS와 같은 순서의 값
for i, key in ipairs(KEYS) do redis.call('SET', key, ARGV[i + 1], 'PX', ARGV[1]) end
return #KEYS
```

**이 방식을 고른 이유** (측정으로 바꿈)

| 시도 | 결과 |
|---|---|
| 상품마다 `SET` | miss 한 번에 왕복 20번. miss가 DB만 쓸 때보다 70~100ms 느림 |
| Spring Data Redis 파이프라인 | 공유 연결로 실행할 수 없어 **실행마다 전용 연결을 새로 맺음**. master·replica 구성이라 한 번에 연결이 약 5개 생김(miss 10번에 새 연결 53개). 여전히 약 55ms 느림 |
| **Lua 스크립트 한 번** | 공유 연결에서 왕복 1번. miss 10번에 새 연결 4개(백그라운드 연결), miss가 DB만 쓸 때와 같은 수준 |

추가 이점: Redis가 응답하지 않을 때도 대기 시간을 상품마다가 아니라 **한 번만** 쓴다.

### 5-3. 오류 처리와 지표

| 상황 | 동작 |
|---|---|
| Redis 오류(연결 실패, 시간 초과, 거절) | 호출자에게 던지지 않는다. 조회는 miss처럼 비어 있고 DB에서 읽는다. 저장 실패는 무시하고 조회 결과는 그대로 응답한다 |
| 저장된 값을 JSON으로 읽지 못함 | 캐시 오류로 보고 DB에서 읽는다 |
| DB 오류 | 숨기지 않는다. 빈 목록이나 가격 0으로 바꾸지 않는다 |

지표: `product.list.cache`, `product.cache` 카운터에 `op`(get·put)와 `result`(hit·miss·error·ok) 태그.

**이유**: "Redis가 비어 있어서 DB로 간 것(miss)"과 "Redis가 고장 나서 DB로 간 것(error)"은 운영에서 다르게 대응해야 한다. 지표로 둘을 구분할 수 있게 했다. 테스트도 이 지표로 hit·miss·error를 확인한다.

---

## 6. Redis 서버 (환경별)

| 환경 | 구성 | 영속화 | 용도 |
|---|---|---|---|
| 로컬 개발 (`docker/infra-compose.yml`) | `redis:7.0` master(6379) + replica(6380, `replicaof`, 읽기 전용) | AOF 켬 | 템플릿 기본 로컬 환경. 바꾸지 않음 |
| 실습 측정 (`docker/perf-compose.yml`) | `redis:latest`(8.10.2) 1대, `127.0.0.1:6389`. 앱은 master·replica 모두 이 한 대를 가리킴 | **끔** (`--save ""`, `--appendonly no`) | 캐시 측정 전용 |
| 테스트 (Testcontainers) | `redis:latest` 1대. master·replica 설정 모두 같은 컨테이너 | 기본 | 자동 테스트 |
| 장애 테스트 | 위와 별도의 전용 컨테이너, 호스트 포트 고정 | 기본 | Redis를 실제로 멈췄다가 다시 시작 |

**실습 Redis의 영속화를 끈 이유**: 캐시는 잃어도 되는 복사본이다. 재시작하면 빈 캐시로 시작해야 miss·재채움을 측정할 수 있다. 또 실습 DB의 시드를 다시 넣으면 revision이 0으로 돌아가는데, 같은 revision의 옛 캐시가 남아 있으면 맞는 값처럼 읽힌다. 영속화를 끄고, 시드를 다시 넣을 때 실습 Redis를 비우도록 안내했다.

**실습 Redis를 따로 띄운 이유**: 공유 Redis를 비우거나(`FLUSHALL`) 멈추면 다른 작업에 영향을 준다. 측정·장애 실험은 이 실습 전용 Redis에서만 했다.

**장애 테스트용 컨테이너를 따로 둔 이유**
- 테스트 전체가 함께 쓰는 Redis를 멈추면 다른 테스트가 깨진다.
- 컨테이너를 재시작하면 호스트 포트가 바뀔 수 있어서 포트를 고정했다. 같은 주소로 다시 붙는지까지 확인하기 위해서다.

**테스트 픽스처를 고친 이유**: 테스트용 Redis 컨테이너의 주소를 생성자에서 넣고 있어서, Redis 설정이 먼저 바인딩되면 테스트가 컨테이너가 아니라 `localhost:6379/6380`으로 접속했다. 지금까지는 테스트에서 Redis를 실제로 쓰는 코드가 없어 드러나지 않았다. MySQL 픽스처처럼 클래스 초기화 시점(static 블록)에 넣도록 바꿨다.

---

## 7. 앱 실행 인자 (실습 측정 시)

```bash
./gradlew :apps:commerce-api:bootRun --args='--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3309/loopers_perf --spring.jpa.hibernate.ddl-auto=none --datasource.redis.master.port=6389 --datasource.redis.replicas[0].host=localhost --datasource.redis.replicas[0].port=6389'
```

**`replicas[0].host`까지 주는 이유**: `replicas`는 목록 속성이라, 명령줄에서 `port`만 주면 설정 파일의 목록 전체가 교체되어 host가 비어 버린다(`Host name must not be null or empty`로 앱이 뜨지 않음). 그래서 host와 port를 함께 준다.

---

## 8. 손대지 않은 것

| 항목 | 현재 상태 | 비고 |
|---|---|---|
| 연결 풀 | 설정 안 함 (Lettuce 공유 연결 하나) | Lettuce는 연결 하나를 여러 스레드가 함께 쓰므로 일반 명령에는 풀이 필요 없다. 파이프라인처럼 전용 연결이 필요한 기능은 쓰지 않는다(5-2절) |
| `maxmemory`, 축출 정책 | Redis 기본값 | 키 수는 TTL과 "앞 5페이지만 캐시"로 제한했다. 운영 환경이라면 메모리 상한과 축출 정책을 따로 정해야 한다 |
| 캐시 stampede 대비 | 없음 | 브랜드 없는 좋아요순은 miss일 때 약 2.4초가 걸린다. 그 순간 같은 요청이 몰리는 경우는 이번에 다루지 않았다 |
| Redis 기반 버전 포인터 | 쓰지 않음 | 신선도 기준(revision)은 Redis가 아니라 DB에 둔다. Redis가 실패해도 최신성이 깨지지 않게 하기 위해서다 |
