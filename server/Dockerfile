FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY spinus_server ./spinus_server
ENV SPINUS_MUSIC=/music SPINUS_DATA=/data SPINUS_PORT=8484 PYTHONUNBUFFERED=1
EXPOSE 8484
VOLUME ["/data"]
HEALTHCHECK --interval=60s --timeout=5s CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8484/api/info')"
CMD ["python", "-m", "spinus_server"]
