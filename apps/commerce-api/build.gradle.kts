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

    // PG HTTP·회로 (버전은 Boot/Cloud BOM 관리값)
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("io.github.resilience4j:resilience4j-spring-boot3")

    // security (관리자 API 접근 경계)
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

    // 로컬 PG 앱 (설계 2.10). 테스트가 같은 JVM 에서 시작한다
    testImplementation(project(":apps:pg-simulator"))

    // ArchUnit
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}

plugins {
    checkstyle
}

// C10 종단 검증이 별도 JVM 으로 띄울 streamer 실행물. API 테스트 classpath 에는 넣지 않고 경로만 넘긴다.
val streamerBootJar = project(":apps:commerce-streamer").tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")

// O11(설계 W7.4-2): W6 준비→W7 복구 E2E 는 빈 fixture DB 가 필요해 새 worker JVM 의 전용 작업에서만 실행한다.
val w6OutboxRecoveryClass = "com.loopers.infrastructure.order.W6OutboxRecoveryEndToEndIntegrationTest"

tasks.test {
    dependsOn(streamerBootJar)
    // classpath 밖의 실행물이라 dependsOn 만으로는 내용 변경이 test 입력에 잡히지 않는다. jar 내용을 입력 파일로 추적한다.
    inputs.file(streamerBootJar.flatMap { it.archiveFile })
        .withPropertyName("c10StreamerBootJar")
        .withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dc10.streamer.boot-jar=" + streamerBootJar.get().archiveFile.get().asFile.absolutePath)
    })
    // 일반 test 에서는 O11 클래스 하나만 정확히 제외한다. 다른 테스트는 제외하지 않는다.
    filter { excludeTestsMatching(w6OutboxRecoveryClass) }
    // 08-3: 한 worker 의 캐시된 Spring context(최대 32)가 기본 512m 를 넘겨 OutOfMemoryError 가 났다. 기대·시간 한도는 그대로다.
    maxHeapSize = "1g"
}

val w6OutboxRecoveryTest = tasks.register<Test>("w6OutboxRecoveryTest") {
    description = "O11: W6 준비 상태를 같은 DB 의 W7 로 바꿔 명시 복구하는 실제 E2E 를 새 worker JVM 에서 실행한다."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    dependsOn(tasks.named("testClasses"), streamerBootJar)
    inputs.file(streamerBootJar.flatMap { it.archiveFile })
        .withPropertyName("c10StreamerBootJar")
        .withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dc10.streamer.boot-jar=" + streamerBootJar.get().archiveFile.get().asFile.absolutePath)
    })
    // root 의 tasks.test 설정은 이 작업에 적용되지 않으므로 같은 값을 명시한다. datasource 주소는 worker 의 fixture 가 정한다.
    useJUnitPlatform()
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    maxHeapSize = "1g"
    filter {
        includeTestsMatching(w6OutboxRecoveryClass)
        isFailOnNoMatchingTests = true
    }
    maxParallelForks = 1
    forkEvery = 1
    mustRunAfter(tasks.test)
    // 로컬 외부 자원(broker·child·fixture)의 실제 관찰이라 up-to-date·build cache 결과로 대체하지 않는다.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("실제 외부 자원 관찰 E2E") { true }
    // 결과(XML/HTML)와 JaCoCo exec 는 작업 이름별 기본 경로(test-results/w6OutboxRecoveryTest, reports/tests/w6OutboxRecoveryTest,
    // jacoco/w6OutboxRecoveryTest.exec)로 일반 test 와 분리된다.
}

tasks.check {
    dependsOn(w6OutboxRecoveryTest)
}

// 기존 report 는 jacoco/*.exec 를 모두 수집한다. 전용 작업의 exec 도 report 전에 만들어지도록 순서만 더한다.
tasks.withType<JacocoReport> {
    mustRunAfter(w6OutboxRecoveryTest)
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
