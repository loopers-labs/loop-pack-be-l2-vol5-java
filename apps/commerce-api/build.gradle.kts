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

    // security (관리자 API 경계)
    implementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")

    // resilience (W5 PG 호출: timeout·retry·circuit breaker)
    implementation("io.github.resilience4j:resilience4j-spring-boot3:${project.properties["resilience4jVersion"]}")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))

    // 비동기 결과를 기다려 확인 (W5 발송·결과 반영)
    testImplementation("org.awaitility:awaitility")

    // architecture
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}
