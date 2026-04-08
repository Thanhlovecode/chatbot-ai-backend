@echo off
REM ╔══════════════════════════════════════════════════════════════════╗
REM ║  run-prod.bat — Khởi động môi trường PRODUCTION                ║
REM ║                                                                  ║
REM ║  • Build + chạy spring-ai app từ Dockerfile                    ║
REM ║  • Infra: PostgreSQL, Redis, Qdrant                            ║
REM ║  • Monitoring: Prometheus, Grafana, Exporters                   ║
REM ║  • Tất cả kết nối qua Docker internal network                  ║
REM ╚══════════════════════════════════════════════════════════════════╝

echo.
echo ══════════════════════════════════════════════════════
echo   Starting PRODUCTION environment...
echo   All services running in Docker containers
echo   Building spring-ai app from Dockerfile...
echo ══════════════════════════════════════════════════════
echo.

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build --remove-orphans

echo.
echo ═══════════════════════════════════════════════════════
echo   PRODUCTION containers started!
echo.
echo   App:          http://localhost:8080
echo   Grafana:      http://localhost:3001
echo   Prometheus:   http://localhost:9090
echo ═══════════════════════════════════════════════════════
echo.
pause
