plugins {
    checkstyle
}

// commerce-api와 같은 검사(docs/week6/decisions.md D-24) — 이번 주에 streamer에 계층이 처음 생긴다.
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

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // architecture
    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // 구매 활동 소비 테스트용 broker (docs/week6/decisions.md D-24) — 버전은 Spring Boot가 관리한다
    testImplementation("org.testcontainers:kafka")
    // 실험 C-3 전용 MySQL 컨테이너(docs/week6/decisions.md D-21). 공용 픽스처의 mysql 의존은 compile classpath에 오지 않는다
    testImplementation("org.testcontainers:mysql")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
}

// api와 streamer가 같은 계약 JSON을 읽는다(docs/week6/decisions.md D-24). 테스트는 이 경로로 파일을 찾는다.
tasks.test {
    systemProperty("contract.dir", rootProject.file("docs/week6/contract").absolutePath)
    // 실험 C-3(DB 자체를 멈춤)은 느려서 check에서 뺀다(docs/week6/decisions.md D-21). experimentTest로 따로 돌린다.
    useJUnitPlatform {
        excludeTags("experiment")
    }
}

val experimentTest by tasks.registering(Test::class) {
    description = "@Tag(\"experiment\") 실험만 실행한다(check에 묶지 않는다). docs/week6/decisions.md D-21 C-3"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxParallelForks = 1
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    systemProperty("contract.dir", rootProject.file("docs/week6/contract").absolutePath)
    jvmArgs("-Xshare:off")
    useJUnitPlatform {
        includeTags("experiment")
    }
}
