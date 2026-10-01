@echo off
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
call mvn %*
