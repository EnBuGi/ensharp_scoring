FROM gradle:8.7-jdk21-alpine

WORKDIR /workspace

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
    testImplementation 'com.h2database:h2:2.2.224'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
EOF

RUN gradle classes testClasses --no-daemon && rm -rf *
