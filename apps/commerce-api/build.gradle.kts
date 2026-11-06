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

    // resilience (PG 조회 서킷브레이커. core 모듈만 쓰고 스타터·AOP 는 넣지 않는다. 설계 5-7 결정 ㉛)
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:${project.properties["resilience4jVersion"]}")

    // security
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

    // architecture
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}

// 7주차 과제 5절 (📄 p.4 5-1, p.6~7): commerce-streamer 의 실제 consumer 를 같은 실행에 연결하는 테스트 전용 소스셋.
// streamer 의존은 이 소스셋에만 붙인다. 기존 src/test 의 classpath 와 제품 runtime(implementation) 의존은 바뀌지 않는다.
// 자기 main·test 출력(가짜 PG·가짜 발행기 재사용)을 streamer jar 보다 앞에 둬서 application.yml 은 commerce-api 의 것이 읽힌다.
val crossAppTestSourceSet: SourceSet = sourceSets.create("crossAppTest")
crossAppTestSourceSet.compileClasspath =
    sourceSets.main.get().output + sourceSets.test.get().output + configurations["crossAppTestCompileClasspath"]
crossAppTestSourceSet.runtimeClasspath = crossAppTestSourceSet.output +
    sourceSets.main.get().output + sourceSets.test.get().output + configurations["crossAppTestRuntimeClasspath"]

configurations["crossAppTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["crossAppTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    "crossAppTestImplementation"(project(":apps:commerce-streamer"))
}

val crossAppTest by tasks.registering(Test::class) {
    description = "commerce-api 와 commerce-streamer 의 실제 consumer 를 한 실행에 연결하는 테스트 (7주차 과제 5절)"
    group = "verification"
    testClassesDirs = crossAppTestSourceSet.output.classesDirs
    classpath = crossAppTestSourceSet.runtimeClasspath
    maxParallelForks = 1
    useJUnitPlatform()
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(crossAppTest) }

