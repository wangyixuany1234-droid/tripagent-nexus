#!/usr/bin/env sh

# Testcontainers does not resolve Docker CLI contexts. Export the active local
# Unix socket when DOCKER_HOST has not already been configured explicitly.
if [ -z "${DOCKER_HOST:-}" ]; then
  docker_context=$(docker context show 2>/dev/null || true)
  if [ -n "$docker_context" ]; then
    context_host=$(
      docker context inspect "$docker_context" \
        --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true
    )
    case "$context_host" in
      unix://*)
        socket_path=${context_host#unix://}
        if [ -S "$socket_path" ]; then
          export DOCKER_HOST=$context_host
        fi
        ;;
    esac
  fi
fi

case "${DOCKER_HOST:-}" in
  unix://*)
    if [ "${DOCKER_HOST#unix://}" != "/var/run/docker.sock" ]; then
      export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-/var/run/docker.sock}
    fi
    ;;
esac
