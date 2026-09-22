# The video grid dashboard: a plain Python service (stdlib HTTP server, the shared
# GroundStation client, pymavlink for the MAVLink comparison panel). It uses no ROS
# API, so it runs on the same Python the rest of the repository targets instead of on
# a ROS base image several hundred megabytes larger.
ARG PYTHON_IMAGE=python:3.14.7-slim-trixie

FROM ${PYTHON_IMAGE} AS python-deps

ENV UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/opt/venv

# A compiler, for a dependency with no wheel yet on this interpreter. It stays in
# this stage: only the finished virtualenv is copied into the image.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/lyrebird

# pyproject.toml + uv.lock are the only dependency source: the dashboard installs the
# shared GroundStation client and its MAVLink decoder from the same lock the tests and
# CI use, rather than from a second hand-maintained list of versions. uv is mounted for
# the build and never reaches the final image.
COPY pyproject.toml uv.lock ./
COPY GroundStation/Python/ GroundStation/Python/
RUN --mount=from=ghcr.io/astral-sh/uv:0.12.17,source=/uv,target=/bin/uv \
    --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-default-groups --group mavlink --no-editable

FROM ${PYTHON_IMAGE}

ENV PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:${PATH}" \
    PORT=8090

WORKDIR /app
COPY --from=python-deps /opt/venv /opt/venv

# The shared client sits inside that virtualenv as a real distribution, so the MAVLink
# panel reads both wires through the same code the rest of the ground station uses
# rather than a second implementation that could drift from it.
COPY GroundStation/video_test/webapp/server.py ./server.py
COPY GroundStation/video_test/webapp/mavlink_debug.py ./mavlink_debug.py
COPY GroundStation/video_test/webapp/video_events.py ./video_events.py
COPY GroundStation/video_test/webapp/public ./public

EXPOSE 8090

CMD ["python", "server.py"]
