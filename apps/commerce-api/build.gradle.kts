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
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.4.0")
    implementation("io.github.resilience4j:resilience4j-core:2.4.0")

    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    implementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
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
    // relay 테스트 하나(없는 topic)가 topic 자동 생성을 끈 전용 broker를 띄운다. 버전은 BOM을 따른다.
    testImplementation("org.testcontainers:kafka")
}

// 주문부터 구매 활동까지 E2E(7주차 4단계). 실제 streamer consumer를 같은 JVM의 별도 컨텍스트로 띄우려고 streamer main 출력을
// 이 source set의 classpath에만 넣는다. 기존 test(ArchUnit 포함)와 제품 runtime에는 streamer가 들어가지 않는다.
// streamer는 boot 앱이라 일반 jar를 만들지 않으므로 jar 의존 대신 main 출력 디렉터리를 쓴다.
// 두 앱 모두 application.yml이 있어 api 쪽이 먼저 읽히도록 streamer 출력을 classpath 맨 뒤에 둔다.
evaluationDependsOn(":apps:commerce-streamer")
val streamerMain = project(":apps:commerce-streamer").extensions.getByType<SourceSetContainer>()["main"]

val e2eTest by sourceSets.creating {
    compileClasspath = sourceSets.main.get().output + sourceSets.test.get().output +
        configurations["e2eTestCompileClasspath"] + streamerMain.output
    runtimeClasspath = output + sourceSets.main.get().output + sourceSets.test.get().output +
        configurations["e2eTestRuntimeClasspath"] + streamerMain.output
}

configurations["e2eTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["e2eTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

val e2eTestTask = tasks.register<Test>("e2eTest") {
    description = "주문 API부터 streamer 구매 활동 consumer까지 한 JVM에서 확인한다."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = e2eTest.output.classesDirs
    classpath = e2eTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    val unitTest = tasks.test.get()
    maxParallelForks = 1
    useJUnitPlatform()
    systemProperties(unitTest.systemProperties)
    environment(unitTest.environment)
    jvmArgs(unitTest.jvmArgs.orEmpty())
}

tasks.check {
    dependsOn(e2eTestTask)
}
