import org.gradle.api.plugins.quality.CheckstyleExtension

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // querydsl
    annotationProcessor("io.github.openfeign.querydsl:querydsl-apt:${project.properties["queryDslVersion"]}:jpa")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation("com.tngtech.archunit:archunit-junit5:${project.properties["archUnitVersion"]}")
}

apply(plugin = "checkstyle")

configure<CheckstyleExtension> {
    toolVersion = project.properties["checkstyleVersion"].toString()
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

// check·build로 실행되면 test 태스크 하나(한 JVM, Spring 컨텍스트 기동 1회)가 slow·example 태그까지 모두 실행한다.
// test를 단독으로 실행할 때만 slow·example 태그를 뺀다.
val runsFullCheck = gradle.startParameter.taskNames.any { it.substringAfterLast(':') in setOf("check", "build") }

tasks.named<Test>("test") {
    useJUnitPlatform {
        if (!runsFullCheck) {
            excludeTags("slow", "example")
        }
    }
}

// 오래 걸리거나 참고용인 테스트(slow, example 태그)를 별도로 묶어 실행하는 태스크
val slowTest by tasks.registering(Test::class) {
    description = "느리거나 예시용(slow, example 태그) 테스트만 실행한다"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("slow", "example")
    }
    maxParallelForks = 1
    systemProperty("file.encoding", "UTF-8")
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    shouldRunAfter(tasks.test)
}
