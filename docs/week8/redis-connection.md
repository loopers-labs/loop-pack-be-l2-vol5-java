# Redis 연결 설정 읽기 — 앱은 어느 Redis에 어떻게 붙나

이 문서는 Java 앱이 Redis에 붙는 설정을 코드에서 읽고 사실만 정리한 것이다. 실행해서 확인한 결과가 아니며, 해석과 선택은 남겨 둔다.

## 먼저 알아 둘 말

| 말 | 뜻 |
| --- | --- |
| master | 쓰기를 받는 원본 Redis 서버 |
| replica | master의 데이터를 복제해 받는 서버. 이 저장소의 로컬 replica는 읽기 전용이다. 복제는 조금 늦을 수 있다 |
| Lettuce | Spring Data Redis가 사용하는 Java Redis 클라이언트 |
| connection factory | Redis 서버 주소·읽기 방식·timeout을 정해 연결을 만드는 객체 |
| template | 코드에서 Redis 명령을 보낼 때 쓰는 객체. 어떤 factory를 쓰는지에 따라 붙는 서버와 읽기 방식이 정해진다 |
| `ReadFrom` | 읽기 명령을 어느 서버로 보낼지. `MASTER`는 항상 master, `REPLICA_PREFERRED`는 가능하면 replica |
| command timeout | 명령 하나의 응답을 기다리는 최대 시간 |
| serializer | Java 값과 Redis에 저장되는 바이트를 서로 바꾸는 방식 |

## 읽은 파일

| 파일 | 내용 |
| --- | --- |
| `modules/redis/src/main/resources/redis.yml` | `datasource.redis` 주소. commerce-api의 `application.yml`이 import한다 |
| `modules/redis/.../config/redis/RedisProperties.java` | `database`, `master`, `replicas` 바인딩 |
| `modules/redis/.../config/redis/RedisConfig.java` | 연결 factory 2개와 template 2개 |
| `modules/redis/src/testFixtures/.../RedisTestContainersConfig.java` | 테스트용 Redis 컨테이너 |
| `modules/redis/src/testFixtures/.../RedisCleanUp.java` | 테스트 정리 유틸 |
| `apps/commerce-api/.../infrastructure/product/ProductCacheRedisConfig.java` | 상품 캐시 전용 연결 |
| `docker/infra-compose.yml` | 로컬 `redis-master`, `redis-readonly` 컨테이너 |

## 주소와 포트

| 환경 | master | replica | 근거 |
| --- | --- | --- | --- |
| local·test·benchmark 프로필 기본값 | `localhost:6379` | `localhost:6380` | `redis.yml` |
| 로컬 Docker | `redis-master` → 6379 | `redis-readonly` → 6380, `--replicaof redis-master 6379`, 읽기 전용 | `infra-compose.yml` |
| 공용 테스트 fixture | 컨테이너 1개의 매핑 포트 | **master와 같은 컨테이너·같은 포트** | `RedisTestContainersConfig`가 system property로 덮어씀 |
| 상품 캐시 테스트 | 테스트가 직접 띄운 컨테이너의 고정 빈 포트 | master와 같음 | `PricePageCacheIntegrationTest`의 `@DynamicPropertySource` |
| 쿠폰 장애 테스트 | 전용 `redis:7.0.15` 컨테이너의 매핑 포트 | master와 같음 | `CouponRedisFailureTest`의 `@DynamicPropertySource`. 이 컨테이너만 pause/unpause |

- 공용 fixture에서는 replica 항목도 master 컨테이너를 가리킨다. replica를 따로 띄우지 않는다.
- 상품 캐시 테스트는 컨테이너를 멈췄다가 다시 시작해도 같은 포트로 붙도록 포트를 고정해 바인딩한다. 장애 실험 방식의 참고 사례다.

## 연결 factory와 template

| bean | 읽기 방식 | 선언 타입 | 비고 |
| --- | --- | --- | --- |
| `defaultRedisConnectionFactory` (`@Primary`) | `ReadFrom.REPLICA_PREFERRED` | `LettuceConnectionFactory` | `RedisStaticMasterReplicaConfiguration` |
| `masterRedisConnectionFactory` (`@Qualifier("redisConnectionMaster")`) | `ReadFrom.MASTER` | `LettuceConnectionFactory` | 같은 master·replica 목록 |
| `defaultRedisTemplate` (`@Primary`) | 위 기본 factory | `RedisTemplate<String, String>` | |
| `masterRedisTemplate` (`@Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)` = `"redisTemplateMaster"`) | master factory | `RedisTemplate<String, String>` | 상수가 `public`이라 주입 시 그대로 쓸 수 있다 |
| `productCacheRedisTemplate` | master 주소에 standalone 연결 | `StringRedisTemplate` | commerce-api 상품 캐시 전용 |

- 두 `RedisTemplate`은 key·value·hash key·hash value serializer가 모두 `StringRedisSerializer`다.
- 한정자 없이 `RedisTemplate<String, String>`을 주입하면 `@Primary`인 `REPLICA_PREFERRED` template이 들어온다.

## timeout

| 연결 | command timeout | connect timeout | 근거 |
| --- | --- | --- | --- |
| `RedisConfig`의 두 factory | 선택 설정 `datasource.redis.command-timeout`. 값이 없으면 Lettuce 기본값 60초. **commerce-api는 200ms 지정**(처음 읽었을 때는 설정 자체가 없었다. 쿠폰 발급 판단·세마포어가 이 연결을 쓴다) | 지정하지 않음 | `RedisConfig`, commerce-api `application.yml` |
| `productCacheRedisTemplate` | 200ms | 200ms (`SocketOptions`) | `ProductCacheRedisConfig` |

`RedisConfig`는 연결 factory를 직접 만들기 때문에, `spring.data.redis.timeout` 같은 Spring Boot 자동 설정 속성을 추가해도 이 factory에 적용되지 않는다. 설정값만 추가하고 적용됐다고 가정하지 않고, 실제 실행으로 확인해야 한다.

현재는 `RedisCouponIssuePermitsTest`에서 실제 factory가 master 연결이고 command timeout이 설정값인지 검사한다. 앱이 쓰는 Redis를 `pause`했을 때의 동작은 부하 실험([experiments.md](experiments.md) 6단계)에서 봤다(발급 요청이 약 210ms 뒤 503, 톰캣 사용 중 스레드 약 130). 연결 수립 timeout이나 failover 검증과는 구분한다. 장애 뒤 복구 절차 문서는 아직 없다(미정).

## 테스트 정리 유틸

`RedisCleanUp.truncateAll()`은 `flushAll`이다. 연결된 Redis의 모든 키를 지운다. 과제는 “자기 테스트 자원만 정리”를 요구한다.

## 직접 확인해 볼 질문

- 공용 테스트 fixture에서 master와 replica가 같은 컨테이너라면, 테스트로 `REPLICA_PREFERRED`와 `MASTER`의 차이를 관찰할 수 있을까?
- 수용 판단에 쓸 연결의 command timeout을 몇으로 둘지, 그 값이 실제 연결에 적용됐는지는 어떻게 확인할까?
- 수용 테스트가 정리할 대상은 정확히 어떤 키일까?
