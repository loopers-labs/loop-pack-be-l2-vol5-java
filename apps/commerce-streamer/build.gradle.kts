import com.github.davidmc24.gradle.plugin.avro.GenerateAvroJavaTask

plugins {
    id("com.github.davidmc24.gradle.plugin.avro")
}

// 앱 간 메시지 계약 원본(루트)에서 클래스를 생성한다.
tasks.named<GenerateAvroJavaTask>("generateAvroJava") {
    source(rootProject.file("contracts/avro"))
}

// 학습용 실험 테스트는 기본 test/check 에서 빼고 experimentTest 로만 돌린다.
tasks.test {
    useJUnitPlatform {
        excludeTags("experiment")
    }
}

tasks.register<Test>("experimentTest") {
    description = "@Tag(\"experiment\") 실험 테스트만 실행한다."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxParallelForks = 1
    useJUnitPlatform {
        includeTags("experiment")
    }
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // 메시지 계약(Avro)
    implementation("org.apache.avro:avro:${project.properties["avroVersion"]}")

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
}
