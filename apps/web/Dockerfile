FROM node:22-alpine AS builder

WORKDIR /workspace/apps/web

ARG VITE_API_BASE_URL=http://localhost:8080
ARG VITE_USER_AUTH_ENABLED=true
ARG VITE_OIDC_AUTHORITY
ARG VITE_OIDC_CLIENT_ID
ARG VITE_OIDC_SCOPE="openid profile"
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_USER_AUTH_ENABLED=$VITE_USER_AUTH_ENABLED \
    VITE_OIDC_AUTHORITY=$VITE_OIDC_AUTHORITY \
    VITE_OIDC_CLIENT_ID=$VITE_OIDC_CLIENT_ID \
    VITE_OIDC_SCOPE=$VITE_OIDC_SCOPE

COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund

COPY apps/web/ ./
COPY qa/config/thresholds.json /workspace/qa/config/thresholds.json
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY apps/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /workspace/apps/web/dist /usr/share/nginx/html

USER 101:101

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=10 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
