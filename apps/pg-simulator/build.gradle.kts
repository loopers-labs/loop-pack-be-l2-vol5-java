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
    // JSON·콜백 전송 (버전은 Boot BOM 관리값)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.apache.httpcomponents.client5:httpclient5")

    testImplementation("org.awaitility:awaitility")
}

// commerce-api 테스트가 이 모듈을 의존할 수 있도록 bootJar 와 함께 일반 jar 도 만든다(설계 2.10).
tasks.named<Jar>("jar") {
    enabled = true
}
