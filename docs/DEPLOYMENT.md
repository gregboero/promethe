# Deployment Guide

> Deploy Prométhé locally, on Docker, a VPS, or the cloud.

## 1. Local development

```bash
# Unified entry point — launches the embedded gateway + Desktop UI
./gradlew composeApp:run

# Or in daemon mode (gateway only, no UI)
./gradlew composeApp:run --args="--daemon"

# Or standalone gateway (without --cli/--connect modes)
./gradlew gateway:run
```

The gateway listens on `http://localhost:8080`. The Desktop app connects to it automatically.

## 2. Docker (single container)

### Build

```bash
./gradlew gateway:shadowJar
docker build -t promethe-gateway .
```

### Run

```bash
docker run -d \
  --name promethe \
  -p 8080:8080 \
  -e LLM_PROVIDER=openrouter \
  -e OPENROUTER_API_KEY=sk-or-... \
  -e LLM_MODEL=provider/exact-model-id \
  -v promethe-home:/home/promethe/.promethe \
  promethe-gateway
```

All runtime data (database, skills, plugins, profiles) is stored under
`/home/promethe/.promethe/` inside the container. The named volume `promethe-home`
persists this data across container restarts.

### Dockerfile (multi-stage)

- **Stage 1**: `eclipse-temurin:21-jdk-alpine` — builds the fat JAR
- **Stage 2**: `eclipse-temurin:21-jre-alpine` — minimal runtime
- Runs as the non-root user `promethe`
- Built-in healthcheck: `wget http://localhost:8080/health`
- JVM tuning: `-Xmx512m -XX:+UseG1GC`

## 3. Docker Compose (combinable overlays)

The base `docker-compose.yml` runs Prométhé standalone with embedded memory and direct
provider API keys. Add overlays for additional services:

```bash
# Configure the API keys
cp .env.example .env
# Edit .env with your keys

# Base (standalone, embedded memory)
docker compose up --build -d

# With LiteLLM proxy
docker compose -f docker-compose.yml -f compose.litellm.yaml up --build -d

# With Honcho memory
docker compose -f docker-compose.yml -f compose.honcho.yaml up --build -d

# With TencentDB Agent Memory
docker compose -f docker-compose.yml -f compose.tencent.yaml up --build -d

# Combine: LiteLLM + Honcho
docker compose -f docker-compose.yml -f compose.litellm.yaml -f compose.honcho.yaml up --build -d
```

### Overlay matrix

| Overlay | Services added | Key env vars |
|---|---|---|
| *(base)* | promethe-core:8080 | `LLM_PROVIDER`, `MEMORY_PROVIDER=embedded` |
| `compose.litellm.yaml` | litellm:4000 | `LLM_PROVIDER=litellm`, `LLM_BASE_URL=http://litellm:4000` |
| `compose.honcho.yaml` | honcho:8001, honcho-db | `MEMORY_PROVIDER=honcho`, `HONCHO_URL=http://honcho:8001` |
| `compose.tencent.yaml` | tencent-memory:8420 | `MEMORY_PROVIDER=tencent`, `TENCENT_MEMORY_URL=http://tencent-memory:8420` |
| `compose.remote.yaml` | Caddy TLS reverse proxy | `PROMETHE_DOMAIN`, `PUBLIC_BASE_URL`, `CORS_ALLOWED_ORIGINS`, `PROMETHE_MASTER_KEY` |

All overlays are combinable — use multiple `-f` flags to mix LLM proxy with memory providers.

### Validate overlay merges

```bash
# Check resolved config without starting services
docker compose -f docker-compose.yml -f compose.litellm.yaml config
```

### Opt-in remote HTTPS profile

Point `PROMETHE_DOMAIN` at the host, then set `PUBLIC_BASE_URL=https://<domain>`, the exact browser
origin in `CORS_ALLOWED_ORIGINS`, and a master key generated with `openssl rand -base64 32`. Initialize
the single owner locally through stdin before starting the public proxy:

```bash
read -rsp "Owner password: " PROMETHE_OWNER_PASSWORD; echo
printf '%s\n' "$PROMETHE_OWNER_PASSWORD" | docker compose \
  -f docker-compose.yml -f compose.remote.yaml run --rm -T promethe-core \
  owner init --password-stdin --user owner
unset PROMETHE_OWNER_PASSWORD

docker compose -f docker-compose.yml -f compose.remote.yaml up --build -d
```

The base gateway port remains published on host loopback only. Caddy is the sole public listener and obtains
the TLS certificate automatically. Never publish port 8080 directly for a remote installation.

## 4. VPS deployment (production)

### systemd service

```ini
# /etc/systemd/system/promethe.service
[Unit]
Description=Promethe Gateway
After=network.target

[Service]
Type=simple
User=promethe
WorkingDirectory=/opt/promethe
Environment=HOME=/opt/promethe
ExecStart=/usr/bin/java -Xmx1g -XX:+UseG1GC -jar gateway-all.jar
Restart=always
RestartSec=5
EnvironmentFile=/opt/promethe/.env

[Install]
WantedBy=multi-user.target
```

`PrometheHome` resolves its base directory from the `user.home` JVM system property, which the JVM
sets from the `HOME` environment variable — set `Environment=HOME=/opt/promethe` explicitly (as
above) so the database ends up at the predictable `/opt/promethe/.promethe/promethe.db` rather
than whatever home directory the `promethe` system account happens to have.

```bash
sudo systemctl enable promethe
sudo systemctl start promethe
```

### Reverse proxy — Nginx

```nginx
server {
    listen 443 ssl http2;
    server_name promethe.example.com;

    ssl_certificate /etc/letsencrypt/live/promethe.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/promethe.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 300s;
    }
}
```

### Reverse proxy — Caddy (simple alternative)

```
promethe.example.com {
    reverse_proxy localhost:8080
}
```

Caddy automatically handles TLS via Let's Encrypt.

## 5. Cloud deployment

### Railway

```bash
# Install the Railway CLI
npm i -g @railway/cli

# Deploy
railway init
railway up
```

Environment variables to configure in the Railway dashboard.

### Fly.io

```bash
fly launch --dockerfile Dockerfile
fly secrets set OPENROUTER_API_KEY=sk-or-...
fly deploy
```

### DigitalOcean App Platform

1. Connect the GitHub repo
2. Select the Dockerfile
3. Configure the environment variables
4. Deploy

## 6. Database

### Location

`DatabaseFactory` (`shared/src/jvmMain/kotlin/dev/promethe/db/DatabaseFactory.kt`) resolves the
JDBC URL in this order: explicit `url` argument → `PROMETHE_DB_URL` env var → default
`jdbc:sqlite:~/.promethe/promethe.db` (via `PrometheHome.dbFile`).

- Local (bare metal): `~/.promethe/promethe.db` (see systemd note above for `HOME`)
- Docker: the named volume `promethe-home` mounts to `/home/promethe/.promethe/`, which
  matches where `PrometheHome` resolves for the container's `promethe` user.

### Backup

```bash
# Consistent SQLite backup; fails if the destination already exists
java -jar promethe-gateway.jar backup \
  --output /backup/promethe-$(date +%Y%m%d).db

# Docker (the host backup directory is mounted only for this command)
docker compose run --rm -v "$PWD/backups:/backup" promethe-core \
  backup --output /backup/promethe-$(date +%Y%m%d).db
```

The command uses SQLite `VACUUM INTO` and runs `PRAGMA integrity_check` on the resulting file. Schedule
the same command from cron or a systemd timer; do not copy a live SQLite file directly.

### Restore

```bash
# Stop the service so no process has the active database open
sudo systemctl stop promethe

# Validate and restore. The replaced database is preserved beside the active file.
java -jar promethe-gateway.jar restore --input /backup/promethe-20260610.db

# Restart
sudo systemctl start promethe
```

For Docker, stop `promethe-core`, then run the same `restore --input` command with the backup directory
mounted at `/backup`. A corrupt input is rejected before the active database is touched.

## 7. Monitoring

### Health check

```bash
curl http://localhost:8080/health
# → {"status":"ok","uptime":"2h 15m 30s"}
```

### Uptime monitoring (UptimeRobot / Betteruptime)

Configure an HTTP check on `https://promethe.example.com/health` every 60s.

### Observability

Enable in `.env`:

```bash
# Langfuse (LLM traces)
TRACING_BACKEND=langfuse
LANGFUSE_PUBLIC_KEY=pk-...
LANGFUSE_SECRET_KEY=sk-...

# Or OTLP (Jaeger, Grafana)
TRACING_BACKEND=otlp
OTLP_ENDPOINT=http://jaeger:4317
```

## 8. Production security checklist

- [ ] Run as a non-root user
- [ ] TLS enabled (HTTPS)
- [ ] API keys in environment variables (never hardcoded)
- [ ] `~/.promethe/env.json` with 600 permissions
- [ ] Rate limiting enabled
- [ ] CORS configured (allowed domains only)
- [ ] Docker: no `--privileged`
- [ ] Automatic DB backup
- [ ] `/health` monitoring configured
- [ ] Centralized logs

## 9. Scaling

Prométhé is designed to run as a **single instance** (SQLite). To scale:

| Need | Solution |
|---|---|
| More requests | Increase `-Xmx` and the Ktor workers |
| High availability | 2 instances + load balancer + PostgreSQL (migration required) |
| Multi-region | Docker Compose per region + DB sync |
