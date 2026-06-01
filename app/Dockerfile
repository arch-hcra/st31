FROM python:3.9-slim

WORKDIR /app

RUN python3 -m venv /opt/venv

ENV PATH="/opt/venv/bin:$PATH"

COPY requirements.txt .

RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 4080

CMD ["gunicorn", "--bind", "0.0.0.0:4080", "--workers", "2", "--threads", "4", "src.app:app"]
