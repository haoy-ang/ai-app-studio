# ============================================================
# 后端镜像：多阶段构建
#   1) maven 镜像里编译打包
#   2) 只把 jar 拷进 JRE 镜像，最终镜像里没有源码和 Maven
# ============================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom，把依赖层单独缓存：只改源码时这一步能直接命中缓存
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY src ./src
# 测试需要 MySQL/Redis/浏览器，镜像里不跑，交给 CI
RUN mvn -B -q -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 时区：日志和 createTime 都按东八区看，容器默认 UTC 会差 8 小时
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m"

# 只为 healthcheck 装一个 curl；装完立刻清 apt 缓存，别把镜像撑大
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8123

# 用 sh -c 是为了让 JAVA_OPTS 生效（直接 exec 时它不会被 shell 展开）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
