@echo off
REM 在 jar 所在目录启动，使 .\application.yml 与 .\data-standard.db 生效
cd /d "%~dp0"

set JAR=
for %%f in (map-sql-generator-*.jar) do set JAR=%%f
if "%JAR%"=="" (
  echo 未找到 map-sql-generator-*.jar
  exit /b 1
)
if not exist application.yml (
  echo 缺少同目录 application.yml
  exit /b 1
)

java -jar "%JAR%" %*
