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

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
}

// 7주차 과제 5절: commerce-api 의 crossAppTest 소스셋이 이 앱의 실제 consumer 클래스를 테스트 의존으로 쓰려면
// 일반 jar(-plain.jar)가 필요하다. 루트 build 가 apps 하위 앱의 Jar 를 끄는 정책은 다른 앱에 그대로 두고 이 앱만 켠다. bootJar 는 영향 없다.
tasks.jar { enabled = true }
