FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app \
    HISTORY_HOST=0.0.0.0 \
    HISTORY_PORT=8090

WORKDIR /app

COPY history/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

COPY backtest /app/backtest
COPY history /app/history

EXPOSE 8090

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=5 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/healthz', timeout=2)"

CMD ["python", "-m", "history.server"]
