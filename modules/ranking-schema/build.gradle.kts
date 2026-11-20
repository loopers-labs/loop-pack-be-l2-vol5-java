plugins {
    `java-library`
}

dependencies {
    // 주간 랭킹 테이블 4개의 엔티티(테이블 정의)만 담는다. BaseEntity 때문에 jpa 모듈을 api 로 노출한다.
    api(project(":modules:jpa"))
}
