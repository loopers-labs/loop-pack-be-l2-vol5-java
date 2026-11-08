# Loopers Template (Spring + Java)
Loopers 에서 제공하는 스프링 자바 템플릿 프로젝트입니다.

## Getting Started
현재 프로젝트 안정성 및 유지보수성 등을 위해 아래와 같은 장치를 운용하고 있습니다. 이에 아래 명령어를 통해 프로젝트의 기반을 설치해주세요.
### Environment
`local` 프로필로 동작할 수 있도록, 필요 인프라를 `docker-compose` 로 제공합니다.
```shell
docker-compose -f ./docker/infra-compose.yml up
```
### Monitoring
`local` 환경에서 모니터링을 할 수 있도록, `docker-compose` 를 통해 `prometheus` 와 `grafana` 를 제공합니다.
선택적 환경 파일을 지원하는 Docker Compose 2.24 이상을 사용합니다.

애플리케이션 실행 이후, **http://localhost:3000** 로 접속해, admin/admin 계정으로 로그인하여 확인하실 수 있습니다.
```shell
docker compose -f ./docker/monitoring-compose.yml up
```

Slack 알림은 선택 사항입니다. 사용하려면 `docker/grafana/slack.env.example`을 같은 디렉터리의 `slack.env`로 복사하고 `SLACK_WEBHOOK_URI`를 설정합니다. 실제 웹훅은 Git에 올리지 않습니다. 파일이 없거나 URL이 비어 있으면 Outbox Slack 연락처와 알림 규칙을 제외하고 모니터링 스택을 시작합니다. 시작 스크립트는 컨테이너의 임시 복사본을 사용하므로 저장소의 provisioning 파일은 바꾸지 않습니다.

BLOCKED 전환과 발행 지연의 알림 조건은 아래 명령으로 검증합니다. Python 3, Docker와 `prom/prometheus:v2.54.1` 이미지를 사용하며, 이미지가 없으면 Docker가 내려받습니다. 실제 Grafana provisioning 쿼리를 격리된 promtool에서 실행하므로 Slack 메시지를 보내거나 실행 중인 모니터링 스택을 변경하지 않습니다.

```shell
python3 docker/grafana/tests/verify_outbox_alerts.py
```

## About Multi-Module Project
본 프로젝트는 멀티 모듈 프로젝트로 구성되어 있습니다. 각 모듈의 위계 및 역할을 분명히 하고, 아래와 같은 규칙을 적용합니다.

- apps : 각 모듈은 실행가능한 **SpringBootApplication** 을 의미합니다.
- modules : 특정 구현이나 도메인에 의존적이지 않고, reusable 한 configuration 을 원칙으로 합니다.
- supports : logging, monitoring 과 같이 부가적인 기능을 지원하는 add-on 모듈입니다.

```
Root
├── apps ( spring-applications )
│   ├── 📦 commerce-api
│   ├── 📦 commerce-batch
│   └── 📦 commerce-streamer
├── modules ( reusable-configurations )
│   ├── 📦 jpa
│   ├── 📦 redis
│   └── 📦 kafka
└── supports ( add-ons )
    ├── 📦 jackson
    ├── 📦 monitoring
    └── 📦 logging
```

## 문서

Commerce API 설계, 성능 실험과 제안은 [문서 안내](docs/README.md)에서 확인할 수 있습니다.
