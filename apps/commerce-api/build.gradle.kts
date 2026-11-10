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

    // W7 relay를 앱 여러 대 중 한 대만 돌린다 (docs/week7/plan.md D-1 겹침 방지)
    implementation("net.javacrumbs.shedlock:shedlock-spring:${project.properties["shedlockVersion"]}")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:${project.properties["shedlockVersion"]}")

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

// W7 격리 실행 (docs/week7/c7/run.sh): C-7·C-8 W6 누락 복구 2막, C-6 E2E 3막. 스크립트가 띄운 격리 환경이 있어야 하므로 보통 test에서는 뺀다.
tasks.test {
    useJUnitPlatform {
        excludeTags("isolated")
    }
}

tasks.register<Test>("isolatedRun") {
    description = "W7 격리 실행 — run.sh가 --tests로 막마다 부른다 (C-7 2막, C-6 3막)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("isolated")
    }
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    outputs.upToDateWhen { false }
}
