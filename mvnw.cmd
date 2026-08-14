@ECHO OFF
SETLOCAL
SET "MVNW_PROJECT=%~dp0"
SET "MVNW_HOME=%MVNW_PROJECT%.mvn\apache-maven-3.9.11"
IF NOT EXIST "%MVNW_HOME%\bin\mvn.cmd" (
  ECHO Downloading Maven 3.9.11...
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip=Join-Path $env:TEMP 'apache-maven-3.9.11-bin.zip'; Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip' -OutFile $zip; Expand-Archive -Path $zip -DestinationPath '%MVNW_PROJECT%.mvn' -Force"
  IF ERRORLEVEL 1 EXIT /B 1
)
CALL "%MVNW_HOME%\bin\mvn.cmd" %*
