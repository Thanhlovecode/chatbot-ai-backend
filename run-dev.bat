@echo off
REM ╔══════════════════════════════════════════════════════════════════╗
REM ║  run-dev.bat — Khởi động môi trường DEV                        ║
REM ║                                                                  ║
REM ║  • Infra containers: PostgreSQL, Redis, Qdrant (chạy ngầm)     ║
REM ║  • Monitoring: Prometheus, Grafana, Exporters                   ║
REM ║  • Exporters trỏ vào host.docker.internal (PG/Redis local)     ║
REM ║  • App: Chạy riêng trên IDE (IntelliJ)                         ║
REM ╚══════════════════════════════════════════════════════════════════╝

echo.
echo ══════════════════════════════════════════════════════
echo   Starting DEV environment...
echo   App: Run from IDE (IntelliJ / mvn spring-boot:run)
echo   Monitoring: targets = host.docker.internal
echo ══════════════════════════════════════════════════════
echo.

docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --remove-orphans

echo.
echo ═══════════════════════════════════════════════════════
echo   DEV containers started!
echo.
echo   Grafana:      http://localhost:3001  (admin/admin)
echo   Prometheus:   http://localhost:9090
echo.
echo   Next: Start app from IDE with profile=dev or test
echo ═══════════════════════════════════════════════════════
echo.
pause
