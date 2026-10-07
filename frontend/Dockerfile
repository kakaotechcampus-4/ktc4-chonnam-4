# 화면 이미지. 빌드는 Node 이미지에서, 실행은 nginx(root 아닌 사용자, 8080)에서 한다(멀티스테이지).
# 서버에서는 이 nginx 가 80 번을 받아 화면 파일을 내주고 /api/ 는 같은 compose 의 backend:8080 으로 넘긴다.
# CloudFront 원본이 서버 하나라서 화면과 API 가 같은 커밋으로 함께 바뀐다(docs/cd-architecture.md §2).
#
#   docker build --build-arg RELEASE=<커밋 SHA> -t neuringo-web frontend
#   로컬 확인: bash scripts/verify.sh docker   (backend·web·postgres 를 compose.dev.yml 로 띄워 화면과 API 를 본다)
#
# 타입 검사(tsc -b)는 Frontend CI 가 한다. 여기서는 vite build 만 한다(tsc 는 저장소 루트의 contracts/ 를 읽어 빌드 범위가 커진다).
# 베이스는 패치 버전·digest 까지 고정한다. Node 는 frontend/.nvmrc 의 LTS(24)와 같은 메이저다.

FROM node:24.21.0-bookworm-slim@sha256:d6aa754f16b3197301076f047b5def2f02ea1dbbc2ca920407d46d7ec7f87b20 AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
# 배포한 화면은 같은 주소(CloudFront)의 /api/v1 을 부른다. 로컬 개발·E2E 는 이 값이 없어 localhost:8080 이다.
ENV VITE_API_BASE_URL=/api/v1
RUN npx vite build

# 응답 헤더 X-Neuringo-Release 에 커밋 SHA 를 넣는다. 배포 뒤 "지금 보이는 화면이 이 커밋인가"를 이 헤더로 확인한다.
ARG RELEASE=local
RUN case "$RELEASE" in *[!0-9A-Za-z._-]*|"") echo "RELEASE 는 커밋 SHA 여야 한다" >&2; exit 1 ;; esac \
    && sed "s/__RELEASE__/$RELEASE/g" nginx.conf >/tmp/default.conf

FROM nginxinc/nginx-unprivileged:1.30.5-alpine@sha256:15c994d10d6d78658721c3bcafff14cb281fba2a4bdf9d5ba92c416a472516e3

COPY --from=build /tmp/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

# 베이스 이미지가 uid 101(nginx)로 돌고 8080 을 연다. 숫자로 다시 적어 둔다(root 가 아님을 검사기가 알 수 있게).
USER 101
EXPOSE 8080
