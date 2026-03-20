FROM gradle:8.7-jdk21

USER root
RUN apt-get update && apt-get install -y libstdc++6 && rm -rf /var/lib/apt/lists/*
USER gradle


WORKDIR /workspace

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
    echo "package com.example; import org.springframework.boot.autoconfigure.SpringBootApplication; @SpringBootApplication public class DummyApp {}" > src/main/java/com/example/DummyApp.java && \
    gradle classes testClasses --no-daemon && \
    gradle dependencies --no-daemon && \
    rm -rf src build.gradle
