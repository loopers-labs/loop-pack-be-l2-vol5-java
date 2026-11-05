// commerce-api → Kafka → commerce-streamer 를 한 테스트 JVM 에서 잇는 E2E. 제품 runtime 의존(api → streamer)을 만들지 않으려고 별도 모듈에 둔다.
// 두 앱은 일반 jar 를 만들지 않으므로(root build) 컴파일 출력 디렉터리에 직접 의존하고, 앱의 외부 의존은 여기서 같은 것을 선언한다.
// 두 앱의 application.yml 은 이름이 같아 classpath 에서 겹친다: 각 context 가 spring.config.name 으로 고르도록 이름을 바꿔 복사한다.
plugins {
    checkstyle
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

val commerceApi = project(":apps:commerce-api")
val commerceStreamer = project(":apps:commerce-streamer")
evaluationDependsOn(commerceApi.path)
evaluationDependsOn(commerceStreamer.path)

fun Project.sourceSet(name: String): SourceSet = extensions.getByType<SourceSetContainer>()[name]

dependencies {
    // 두 앱의 클래스 (api 는 W6 보존 경로·PG fixture·test fixture 가 있는 test 출력까지)
    testImplementation(files(commerceApi.sourceSet("main").output.classesDirs))
    testImplementation(files(commerceApi.sourceSet("test").output.classesDirs))
    testImplementation(files(commerceStreamer.sourceSet("main").output.classesDirs))

    // 두 앱의 외부 의존 (apps/*/build.gradle.kts 와 같게)
    testImplementation(project(":modules:jpa"))
    testImplementation(project(":modules:redis"))
    testImplementation(project(":modules:kafka"))
    testImplementation(project(":supports:jackson"))
    testImplementation(project(":supports:logging"))
    testImplementation(project(":supports:monitoring"))
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("io.github.resilience4j:resilience4j-circuitbreaker")
    testImplementation("io.github.resilience4j:resilience4j-bulkhead")

    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
}

tasks.processTestResources {
    from(commerceApi.file("src/main/resources/application.yml")) { rename { "e2e-commerce-api.yml" } }
    from(commerceStreamer.file("src/main/resources/application.yml")) { rename { "e2e-commerce-streamer.yml" } }
}

tasks.test {
    // 테스트 클래스마다 새 JVM: MySQL·Kafka·Redis 컨테이너(static)가 클래스마다 새로 뜨고, JVM 이 끝나면 Testcontainers 가 정리한다
    forkEvery = 1
    systemProperty("e2e.outbox-sql", rootProject.file("docs/week7/order-event-outbox.sql").absolutePath)
}
