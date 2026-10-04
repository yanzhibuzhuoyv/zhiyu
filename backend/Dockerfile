FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 ZHIYU_DATA_DIR=/data
WORKDIR /srv
COPY requirements.lock ./
RUN pip install --no-cache-dir -r requirements.lock
COPY app ./app
RUN useradd --uid 10001 --create-home zhiyu && mkdir /data && chown zhiyu:zhiyu /data
USER zhiyu
EXPOSE 8790
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8790"]
