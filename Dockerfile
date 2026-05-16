# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS builder

# 阿里云 Maven 镜像加速
COPY settings.xml /root/.m2/settings.xml

WORKDIR /build
COPY . .
RUN mvn clean package -DskipTests -q

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=builder /build/landgate-app/target/landgate-app-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
