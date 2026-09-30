# 백엔드 실행 이미지. 빌드는 JDK 이미지에서, 실행은 JRE 이미지에서 한다(멀티스테이지).
# 실행 이미지에는 컴파일러·소스·Gradle 캐시가 들어가지 않고, root 가 아닌 사용자로 돈다.
#
#   docker build -t neuringo-backend backend
#   로컬 확인: bash scripts/verify.sh docker   (빌드 → compose 로 기동 → local 프로필로 요청을 받는지)
#
# 베이스는 Temurin 21 LTS(Ubuntu 24.04 noble)를 패치 버전까지 고정한다. 올릴 때는 두 FROM 을 같이 바꾼다.

FROM eclipse-temurin:21.0.12_8-jdk-noble AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src/main src/main

# Gradle 배포본·의존성은 BuildKit 캐시에 둔다. 이미지 층에는 남지 않고, 다음 빌드에서 다시 받지 않는다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -q \
    && cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar')" application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21.0.12_8-jre-noble

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /application

# 자주 바뀌지 않는 층부터 쌓는다(의존성 → 로더 → 스냅숏 의존성 → 애플리케이션). 코드만 바뀌면 마지막 층만 새로 받는다.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# 숫자 UID 로 적어야 쿠버네티스 runAsNonRoot 같은 검사도 root 가 아님을 알 수 있다.
USER 10001:10001
EXPOSE 8080

# 컨테이너 메모리 한도의 75% 까지 힙으로 쓴다(JVM 기본값은 25%).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "application.jar"]
