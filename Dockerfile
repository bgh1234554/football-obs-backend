# 1단계 (build): eclipse-temurin:21-jdk -- Gradle + JDK로 bootJar 빌드
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x ./gradlew && ./gradlew bootJar

# 2단계 (실행): eclipse-temurin:21-jre -- JAR만 복사해서 실행
# Gradle 캐시/소스코드/빌드 도구가 최종 이미지에 포함되지 않는다.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]