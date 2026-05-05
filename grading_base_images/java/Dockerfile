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
}
repositories {
    mavenCentral()
}
dependencies {
    compileOnly 'org.projectlombok:lombok:1.18.30'
    annotationProcessor 'org.projectlombok:lombok:1.18.30'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testImplementation 'org.assertj:assertj-core:3.25.3'
    implementation 'com.h2database:h2:2.2.224'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
EOF

# Force dependency download and verify
RUN mkdir -p src/main/java/com/example src/test/java/com/example && \
    echo "package com.example; import lombok.Data; @Data public class Dummy {}" > src/main/java/com/example/Dummy.java && \
    echo "package com.example; import org.junit.jupiter.api.Test; public class DummyTest { @Test void test() {} }" > src/test/java/com/example/DummyTest.java && \
    echo "--- Build Time: Population (v3) ---" && \
    gradle test --no-daemon --info && \
    echo "--- Build Time: Cache Verification ---" && \
    du -sh /gradle-user-home-cache && \
    rm -rf src build.gradle
