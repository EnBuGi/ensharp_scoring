# 워커 앱 실행용 이미지 (가벼운 JRE 사용)
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# 빌드된 JAR 파일 복사 (Github Actions 단계 3번 결과물)
COPY build/libs/*.jar app.jar

# 실행
ENTRYPOINT ["java", "-jar", "app.jar"]
