@echo off
rem Builds dist\PutScreener.jar with the JDK alone (no NetBeans). Needs the two jars in lib\ (see lib\README.txt).
cd /d "%~dp0"
if not exist lib\TwsApi.jar goto missing
if not exist lib\protobuf-java-*.jar goto missing
rem Oracle's installer puts java and javac on the PATH but not jar: find it in the JDK itself
set "JAR=jar"
where jar >nul 2>nul || for /f "tokens=2 delims==" %%h in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do for /f "tokens=*" %%t in ("%%h") do set "JAR=%%t\bin\jar.exe"
if not exist build\classes mkdir build\classes
javac --release 17 -encoding UTF-8 -cp "lib\*" -d build\classes src\putscreener\*.java || exit /b 1
if not exist dist\lib mkdir dist\lib
>build\manifest.txt echo Class-Path: lib/TwsApi.jar lib/protobuf-java-4.29.5.jar
"%JAR%" --create --file dist\PutScreener.jar --manifest build\manifest.txt --main-class putscreener.PutScreener -C build\classes . || exit /b 1
copy /y lib\*.jar dist\lib\ >nul
copy /y PutScreener.bat dist\ >nul
copy /y putscreener.sh dist\ >nul
echo Built dist\PutScreener.jar. Run dist\PutScreener.bat
exit /b 0
:missing
echo Put TwsApi.jar and protobuf-java-*.jar from IB's TWS API into lib\ first. See lib\README.txt.
exit /b 1
