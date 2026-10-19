ARG PYTHON_IMAGE=python:3.13-slim
FROM ${PYTHON_IMAGE} AS base
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 ZHIYU_DATA_DIR=/data ZHIYU_MEDIA_DIR=/media
WORKDIR /srv
COPY requirements.lock ./
RUN pip install --no-cache-dir -r requirements.lock
COPY requirements-portrait.lock ./
RUN pip install --no-cache-dir --require-hashes -r requirements-portrait.lock
RUN useradd --uid 10001 --create-home zhiyu && mkdir -p /data /media && chown zhiyu:zhiyu /data /media

FROM base AS test
COPY requirements-test.lock ./
RUN pip install --no-cache-dir -r requirements-test.lock
WORKDIR /workspace/backend
COPY app ./app
COPY tests ./tests
COPY pyproject.toml ./
ENV ZHIYU_DATA_DIR=/tmp/zhiyu-test-data ZHIYU_MEDIA_DIR=/tmp/zhiyu-test-media
USER zhiyu
CMD ["python", "-m", "pytest", "-p", "no:cacheprovider", "--cov=app", "--cov-report=term-missing", "--cov-fail-under=80"]

FROM base AS runtime
COPY app ./app
COPY prepare_portrait_model.py ./
RUN python prepare_portrait_model.py --output /opt/zhiyu/models/u2netp.onnx
ENV ZHIYU_PORTRAIT_MODEL_PATH=/opt/zhiyu/models/u2netp.onnx
USER zhiyu
EXPOSE 8790
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8790/health', timeout=2).read()"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8790"]
