@echo off
rem Runs PutScreener. Keep PutScreener.jar and a lib folder (TwsApi.jar, protobuf-java-*.jar) next to this file.
cd /d "%~dp0"
java -cp "PutScreener.jar;lib\*" putscreener.PutScreener %*
if errorlevel 1 pause
