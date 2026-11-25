FROM python:3.12.8-alpine3.21

WORKDIR /app
RUN addgroup -S ev1 && adduser -S -G ev1 ev1
COPY scripts/collect-yahoo-daily.py scripts/ev1-recorder.py scripts/ev1-status.py scripts/ev1-runner.sh /app/scripts/
COPY claudedocs/q1-universe-snapshot-spy-2026-11-20.csv /app/config/q1-universe.csv
RUN chmod 0555 /app/scripts/*.py /app/scripts/ev1-runner.sh \
    && mkdir -p /var/lib/ev1 \
    && chown -R ev1:ev1 /var/lib/ev1
USER ev1:ev1
VOLUME ["/var/lib/ev1"]
ENV EV1_DATA_DIR=/var/lib/ev1 \
    EV1_UNIVERSE=/app/config/q1-universe.csv \
    EV1_PRICE_DIR=/var/lib/ev1/prices \
    EV1_OUT_DIR=/var/lib/ev1/forward \
    EV1_MAX_AGE_SECONDS=3600
HEALTHCHECK --interval=30s --timeout=5s --start-period=300s --retries=3 \
  CMD python3 /app/scripts/ev1-status.py --manifest /var/lib/ev1/forward/manifest.json \
    --runner-status /var/lib/ev1/runner-health.json --max-age-seconds "$EV1_MAX_AGE_SECONDS"
ENTRYPOINT ["/app/scripts/ev1-runner.sh"]
