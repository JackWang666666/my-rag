# LangChain RAG backend.
#
# Two stages so the compiler toolchain needed to build any sdist does not end up
# in the runtime image. Wheels are installed into a virtualenv that is copied
# across wholesale, which is simpler than --user and keeps the paths stable.
FROM python:3.11-slim AS builder

# build-essential: a few pins fall back to an sdist on some platforms rather than
# a wheel. curl is not needed here, only at runtime.
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*

RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

WORKDIR /build
# Copied on its own so the (slow) dependency layer is only rebuilt when the pins
# change, not on every source edit.
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir -r requirements.txt


FROM python:3.11-slim

# curl is what the compose health check calls; without it the check always fails
# and the service never reports healthy.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

COPY --from=builder /opt/venv /opt/venv

WORKDIR /app
COPY main.py ./
COPY app ./app

# Both of these are written at runtime and must be volumes in any deployment
# that expects uploads and the metadata database to survive a redeploy.
# DB_PATH defaults to ../data/rag.db relative to the working directory, which
# would resolve outside /app; pin it explicitly instead.
ENV UPLOAD_DIR=/app/uploads \
    DB_PATH=/app/data/rag.db

# Run as a non-root user. Ownership is set on the directories the app writes to,
# not on the whole tree, so the code stays read-only to the process.
#
# The uid must match the Spring image's: both mount the same volumes for
# /app/data and /app/uploads, and volume ownership is numeric.
RUN useradd --create-home --uid 10001 rag \
    && mkdir -p /app/uploads /app/data \
    && chown -R rag:rag /app/uploads /app/data
USER rag

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:8000/health || exit 1

# No --reload: it watches the filesystem and doubles the process count.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
