ARG PYTHON_IMAGE=python:3.13-slim
FROM ${PYTHON_IMAGE} AS base
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 ZHIYU_DATA_DIR=/data
WORKDIR /srv
COPY requirements.lock ./
RUN pip install --no-cache-dir -r requirements.lock
RUN useradd --uid 10001 --create-home zhiyu && mkdir /data && chown zhiyu:zhiyu /data

FROM base AS test
COPY requirements-test.lock ./
RUN pip install --no-cache-dir -r requirements-test.lock
WORKDIR /workspace/backend
COPY app ./app
COPY tests ./tests
COPY pyproject.toml ./
ENV ZHIYU_DATA_DIR=/tmp/zhiyu-test-data
USER zhiyu
CMD ["python", "-m", "pytest", "-p", "no:cacheprovider", "--cov=app", "--cov-report=term-missing", "--cov-fail-under=80"]

FROM base AS runtime
COPY app ./app
USER zhiyu
EXPOSE 8790
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8790/health', timeout=2).read()"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8790"]
