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

// W7 C-7·C-8 (docs/week7/c7/run.sh): W6 누락 복구 2막. 격리 환경(스크립트가 띄움)이 있어야 하므로 보통 test에서는 뺀다.
tasks.test {
    useJUnitPlatform {
        excludeTags("c7")
    }
}

tasks.register<Test>("c7Recovery") {
    description = "C-7 2막: 1막(W6)이 남긴 격리 DB·broker 위에서 W7 복구를 확인한다 (run.sh가 실행)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("c7")
    }
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    outputs.upToDateWhen { false }
}
