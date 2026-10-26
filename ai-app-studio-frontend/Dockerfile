# ============================================================
# 前端镜像：Vite 构建产物交给 Nginx 托管，同时把 /api 反代到后端
# ============================================================

# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# 部署地址是「浏览器」访问的地址，构建期就写死在产物里，所以要用 build-arg 传
ARG VITE_DEPLOY_DOMAIN=http://localhost:8080
ENV VITE_DEPLOY_DOMAIN=$VITE_DEPLOY_DOMAIN

RUN npm run pure-build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
