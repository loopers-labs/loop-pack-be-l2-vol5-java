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

tasks.test {
    dependsOn(streamerBootJar)
    // classpath 밖의 실행물이라 dependsOn 만으로는 내용 변경이 test 입력에 잡히지 않는다. jar 내용을 입력 파일로 추적한다.
    inputs.file(streamerBootJar.flatMap { it.archiveFile })
        .withPropertyName("c10StreamerBootJar")
        .withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dc10.streamer.boot-jar=" + streamerBootJar.get().archiveFile.get().asFile.absolutePath)
    })
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
