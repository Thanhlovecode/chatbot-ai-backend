@echo off
REM ╔══════════════════════════════════════════════════════════════╗
REM ║  run.bat — Chạy Spring Boot ở môi trường DEV (local)        ║
REM ║  Load env vars từ .env rồi khởi động với profile=dev        ║
REM ╚══════════════════════════════════════════════════════════════╝

echo [INFO] Loading environment variables from .env ...

REM Load từng dòng trong .env (bỏ qua comment '#' và dòng trống)
for /f "usebackq tokens=1,* delims==" %%A in (`findstr /v "^#" .env ^| findstr /v "^$"`) do (
    set "%%A=%%B"
)

echo [INFO] Starting Spring Boot with profile=dev ...
set SPRING_PROFILES_ACTIVE=dev

call mvnw.cmd spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.profiles.active=dev"

pause
