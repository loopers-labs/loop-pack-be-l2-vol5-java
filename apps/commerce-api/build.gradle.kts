plugins {
    checkstyle
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // resilience (PG 조회 재시도·circuit breaker — 스타터·AOP 대신 adapter 안에서 직접 조립한다, docs/week5/design.md D-029)
    implementation("io.github.resilience4j:resilience4j-retry:2.3.0")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
    implementation("io.github.resilience4j:resilience4j-ratelimiter:2.3.0")

    // security (관리자 경로 인가)
    implementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // architecture
    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // acceptance (실제 포트로 HTTP 요청 — 버전은 Spring Boot가 관리)
    testImplementation("io.rest-assured:rest-assured")

    // 테스트 PG fixture (docs/week5/design.md D-033) — standalone은 내부 Jetty·Jackson을 감싸 Spring Boot와 충돌하지 않는다
    testImplementation("org.wiremock:wiremock-standalone:3.13.1")

    // 주문 확정 이벤트 발행 테스트용 broker (docs/week6/decisions.md D-24) — 버전은 Spring Boot가 관리한다
    testImplementation("org.testcontainers:kafka")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}

// api와 streamer가 같은 계약 JSON을 읽는다(docs/week6/decisions.md D-24). 테스트는 이 경로로 파일을 찾는다.
tasks.test {
    systemProperty("contract.dir", rootProject.file("docs/week6/contract").absolutePath)
}
