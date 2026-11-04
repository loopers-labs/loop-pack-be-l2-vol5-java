dependencies {
    implementation("com.github.ben-manes.caffeine:caffeine")
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("io.github.resilience4j:resilience4j-retry:2.3.0")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testImplementation("io.micrometer:micrometer-registry-prometheus")
}

// 설정·장애 조건을 바꿔 결과를 비교하는 실험 테스트(@Tag("experiment"))는 기능 검증과 목적이 달라 평소 test·check에서 뺀다.
// 연결 풀 실험처럼 수백 ms 차이에 결과가 걸린 것도 있어, 필요할 때 experimentTest로 따로 돌린다.
tasks.test {
    useJUnitPlatform {
        excludeTags("experiment")
    }
}

tasks.register<Test>("experimentTest") {
    description = "PG 조회 정책 실험 테스트(@Tag(\"experiment\"))만 실행한다."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxParallelForks = 1
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    systemProperty("experiment.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
    systemProperty("experiment.repositoryRoot", rootProject.projectDir.absolutePath)
    jvmArgs("-Xshare:off")
    useJUnitPlatform {
        includeTags("experiment")
    }
}

// 부하 실험용 가짜 PG를 앱과 별도 프로세스로 띄운다. 테스트 코드의 FakePaymentServer를 쓴다.
tasks.register<JavaExec>("runFakePg") {
    description = "부하 실험용 가짜 PG를 띄운다. -PpgPort, -PcontrolPort로 포트를 바꾼다."
    group = "application"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.loopers.support.payment.FakePaymentServerMain"
    args(
        (findProperty("pgPort") ?: "8090").toString(),
        (findProperty("controlPort") ?: "8091").toString()
    )
}

// 별도 가짜 PG에만 연결하는 짧은 HTTP 경계 비교. 주문 API·DB 부하 결과와 구분한다.
tasks.register<JavaExec>("runPgLoadExperiment") {
    description = "풀 대기·PG 지연과 지속 조회 중 생성 보호를 비교하고 JSON으로 저장한다."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.loopers.infrastructure.payment.PgLoadExperimentMain"
    workingDir(rootProject.projectDir)
    args(
        (findProperty("pgBaseUrl") ?: "http://127.0.0.1:18190").toString(),
        (findProperty("pgControlUrl") ?: "http://127.0.0.1:18191").toString(),
        (findProperty("experimentOutput") ?: "docs/week5/measurements/pg-load.json").toString(),
        (findProperty("experimentRepeats") ?: "3").toString(),
        (findProperty("experimentSet") ?: "all").toString()
    )
}
