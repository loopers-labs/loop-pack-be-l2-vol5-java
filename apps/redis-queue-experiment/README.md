# Redis 대기열·세마포어 실험

이 앱은 Spring Boot로 만든 실험용 서버입니다. 요청을 Redis 대기열에 넣고, 설정한 워커가 꺼내 처리합니다. Lua 스크립트가 전역 permit 수를 확인하고 FIFO 대기열에서 작업을 꺼내 permit을 예약하는 과정을 원자적으로 수행합니다. CPU·메모리·임시 디스크 작업을 제공하며, 데이터베이스는 사용하지 않습니다.

## API

- `POST /experiment/jobs`: 작업을 대기열에 넣고 작업 ID와 `202 Accepted`를 반환합니다.
- `GET /experiment/jobs/{jobId}`: 작업 상태(`QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`)를 반환합니다.
- `GET /experiment/jobs/stats`: 대기 작업 수, 사용 중인 permit 수, permit 용량, 워커 수를 반환합니다.
- `GET /experiment/metrics`: CPU·메모리·JVM 힙·임시 디스크 용량과 프로세스 디스크 읽기·쓰기 바이트를 반환합니다. 호스트가 `/proc/self/io`를 제공하지 않으면 해당 값은 `-1`입니다.

요청 본문은 `{"type":"CPU"}`, `{"type":"MEMORY"}`, `{"type":"DISK"}` 중 하나입니다. CPU 작업은 2048×2048 JPEG를 메모리에서 생성하고, 512×512 썸네일로 변환한 뒤 JPEG로 한 번 인코딩합니다. 메모리 작업은 기본 5초 동안 32MiB를 점유합니다. 디스크 작업은 `/var/tmp`에 16MiB 임시 파일을 쓰고 완료 후 삭제합니다.

```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"type":"CPU"}' http://localhost:8080/experiment/jobs
curl http://localhost:8080/experiment/jobs/stats
curl http://localhost:8080/experiment/metrics
```

## 대기열과 세마포어 동작

접수된 요청은 Redis FIFO 대기열에 `QUEUED` 상태로 저장됩니다. 워커는 Lua 스크립트로 permit 여유를 확인합니다. 자리가 있을 때만 가장 오래 기다린 작업을 꺼내고 permit을 원자적으로 예약합니다. 자리가 없으면 작업은 대기열에 남으며, 워커가 설정된 간격(기본 100ms)으로 다시 확인합니다. permit이 없다는 이유로 요청을 거절하지는 않습니다. 대기열이 `MAX_QUEUE_SIZE`(기본 30건)에 도달했을 때 새 요청에만 `429 Too Many Requests`를 반환합니다.

`WORKER_COUNT`는 해당 앱 인스턴스의 워커 스레드 수이고, `SEMAPHORE_CAPACITY`는 모든 앱 인스턴스가 공유하는 최대 동시 작업 수입니다. 둘은 Redis 연결 설정과 별개입니다. 현재 EC2 실험 설정은 워커 10개, 전역 세마포어 용량 10개입니다.

## 로컬 실행

```bash
REDIS_HOST=127.0.0.1 ./gradlew :apps:redis-queue-experiment:bootRun
```

기본 설정은 전역 세마포어 용량 4개, 워커 10개, 대기열 상한 30건, 메모리·디스크 작업 시간 5초, Redis 확인 간격 100ms입니다. 작업 상태와 permit은 `queue-semaphore-experiment:` 접두사를 사용합니다. EC2 실험은 `t3.micro`(vCPU 2개, 보고된 메모리 약 908MiB)에서 진행했습니다. systemd CPU·메모리 제한은 설정하지 않았습니다.

worker에서 `OutOfMemoryError`가 발생하면 worker 로그에 스택 트레이스를 남깁니다. OOM 원인 분석용 heap dump도 보존하려면 JVM 실행 옵션에 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/tmp/redis-queue-experiment`를 추가하고, 해당 디렉터리를 Java 프로세스가 쓸 수 있게 준비합니다. systemd로 실행할 때 애플리케이션 로그는 `journalctl -u <서비스명>`에서 확인합니다. OOM 시점에는 Redis 작업 상태와 permit이 자동 복구된다고 보장하지 않습니다.

## 실험 결과

워커 수별 p99와 완료 여부는 [워커 동시성 실험 기록](docs/worker-concurrency-experiment.md)에 정리했습니다.

RPS와 세마포어 용량별 비교 계획·결과는 [RPS·세마포어 용량 비교 실험](docs/rps-semaphore-comparison.md)에 기록합니다.

세마포어 용량에 따른 JVM GC 시간과 GC 알고리즘 비교는 [GC·세마포어 비교 실험](docs/gc-semaphore-comparison.md)에 기록합니다.
