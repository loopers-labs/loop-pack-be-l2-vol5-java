import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    checkstyle
}

// Only the current bootJar enters the Docker build context, even if old JARs remain in libs/.
tasks.register<Sync>("prepareDocker") {
    group = "build"
    description = "Prepare the executable JAR for the commerce-api Docker image"
    from(tasks.named<BootJar>("bootJar").flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("docker"))
    rename { "app.jar" }
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

// DomainRulesTest 가 읽는 문서다. 입력으로 걸지 않으면 문서만 고쳤을 때
// test 가 UP-TO-DATE 로 건너뛰어 어긋남이 드러나지 않는다.
tasks.test {
    doFirst {
        systemProperty("commerce.api.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    }
    inputs.files(
        rootProject.file("docs/week2/domain-rules.yaml"),
        rootProject.file("docs/week2/requirements.md"),
    )
        .withPropertyName("domainRuleDocuments")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

sourceSets.main {
    resources.srcDir(rootProject.file("contracts"))
}

// Logback configuration is process-global. The async profile needs its own JVM.
val testAsyncLogging by tasks.registering(Test::class) {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    include("**/ExperimentAsyncHttpTest.class")
}
tasks.test {
    exclude("**/ExperimentAsyncHttpTest.class")
    dependsOn(testAsyncLogging)
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:kafka"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.github.resilience4j:resilience4j-bulkhead:2.4.0")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.4.0")
    implementation("io.github.resilience4j:resilience4j-retry:2.4.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // admin boundary
    implementation("org.springframework.boot:spring-boot-starter-security")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation("com.networknt:json-schema-validator:2.0.4")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")

    // architecture test
    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // admin HTTP test
    testImplementation("org.springframework.security:spring-security-test")

    // OpenAPI contract test
    testImplementation("com.atlassian.oai:swagger-request-validator-mockmvc:${project.properties["openApiRequestValidatorVersion"]}")
}

val week6E2eTest by sourceSets.creating {
    java.srcDir("src/week6E2eTest/java")
    resources.srcDir("src/week6E2eTest/resources")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val week6E2eTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

val commerceStreamer = project(":apps:commerce-streamer")
val commerceStreamerMain = commerceStreamer.extensions
    .getByType<org.gradle.api.tasks.SourceSetContainer>().named("main")

dependencies {
    week6E2eTestImplementation(files(commerceStreamerMain.get().output))
}

tasks.named(week6E2eTest.compileJavaTaskName) {
    dependsOn(commerceStreamer.tasks.named("compileJava"))
    dependsOn(commerceStreamer.tasks.named("processResources"))
}

tasks.register<Test>("week6E2eTest") {
    description = "Runs Week 6 order publication to purchase activity end-to-end test"
    group = "verification"
    testClassesDirs = week6E2eTest.output.classesDirs
    classpath = week6E2eTest.runtimeClasspath
    useJUnitPlatform()
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    dependsOn(tasks.named(week6E2eTest.classesTaskName))
}
