FROM gradle:8.7-jdk21

USER root
RUN mkdir -p /gradle-user-home-cache && chown gradle:gradle /gradle-user-home-cache
USER gradle

WORKDIR /workspace

# Use a custom home directory that is NOT a volume
ENV GRADLE_USER_HOME=/gradle-user-home-cache

# Pre-cache common dependencies by running a dummy build
COPY <<EOF build.gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.2.5'
    id 'io.spring.dependency-management' version '1.1.4'
}
repositories {
    mavenCentral()
}
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'com.h2database:h2'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.assertj:assertj-core'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
EOF

# Add dummy source to ensure dependencies are actually downloaded and indexed
RUN mkdir -p src/main/java/com/example src/test/java/com/example && \
    echo "package com.example; import lombok.Data; @Data public class Dummy {}" > src/main/java/com/example/Dummy.java && \
    echo "package com.example; import org.junit.jupiter.api.Test; import org.springframework.boot.test.context.SpringBootTest; @SpringBootTest(classes = Dummy.class) public class DummyTest { @Test void contextLoads() {} }" > src/test/java/com/example/DummyTest.java && \
    echo "--- Build Time: Population (v3) ---" && \
    gradle test --no-daemon --info && \
    echo "--- Build Time: Cache Size ---" && \
    du -sh /gradle-user-home-cache && \
    rm -rf src build.gradle
