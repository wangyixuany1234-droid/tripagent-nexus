FROM ghcr.io/astral-sh/uv:0.12.9 AS uv

FROM python:3.12.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_NO_CACHE=1

RUN apt-get update \
    && apt-get -o Dpkg::Options::=--force-confold upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends \
        poppler-utils \
        tesseract-ocr \
        tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 tripagent \
    && useradd --system --uid 10001 --gid tripagent --create-home tripagent \
    && mkdir -p /app /var/lib/tripagent/lancedb \
    && chown -R tripagent:tripagent /app /var/lib/tripagent

WORKDIR /app
COPY --from=uv /uv /uvx /bin/
COPY pyproject.toml pyproject.toml
COPY uv.lock uv.lock
RUN uv sync --frozen --extra dev --no-install-project

COPY src src
COPY tests tests
RUN uv sync --frozen --extra dev

USER 10001:10001
ENV PATH="/app/.venv/bin:$PATH"

EXPOSE 8000
CMD ["uvicorn", "trip_agent_ai.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
