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

    // resilience (PG 조회 경로의 재조회·회로 차단기. 스타터·AOP 없이 코드로 조립한다)
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:${project.properties["resilience4jVersion"]}")
    implementation("io.github.resilience4j:resilience4j-retry:${project.properties["resilience4jVersion"]}")
    // 쿠폰 발급 API 진입의 전역 Rate limiter (docs/week8 설계서 4·13절)
    implementation("io.github.resilience4j:resilience4j-ratelimiter:${project.properties["resilience4jVersion"]}")

    // security (관리자 접근 제어)
    implementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))

    // architecture test
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}

// ---- W7 E2E: 주문 API부터 실제 구매 활동 consumer까지 한 번에 확인하는 테스트 (docs/week7/design.md 5절 결정 B) ----
// 두 앱의 @SpringBootApplication이 같은 패키지(com.loopers)에 있어, 일반 test classpath에 commerce-streamer를 넣으면 기존 테스트의 설정 탐색·component scan이 깨진다.
// 그래서 E2E만 별도 source set에서 streamer를 classpath에 둔다. 제품 runtime 의존성(implementation)에는 streamer가 없다.
val e2eTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

configurations[e2eTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[e2eTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    "e2eTestImplementation"(project(":apps:commerce-streamer"))
}

val e2eTestTask = tasks.register<Test>("e2eTest") {
    description = "주문 API → Outbox → relay → Kafka → 구매 활동 consumer를 실제 DB·broker로 잇는 E2E 테스트"
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = e2eTest.output.classesDirs
    classpath = e2eTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    // 기존 test task와 같은 실행 조건
    maxParallelForks = 1
    useJUnitPlatform()
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
}

tasks.check {
    dependsOn(e2eTestTask)
}
