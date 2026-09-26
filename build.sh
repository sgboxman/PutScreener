#!/bin/sh
# Builds dist/PutScreener.jar with the JDK alone (no NetBeans). Needs the two jars in lib/ (see lib/README.txt).
cd "$(dirname "$0")" || exit 1
if [ ! -f lib/TwsApi.jar ] || ! ls lib/protobuf-java-*.jar >/dev/null 2>&1; then
  echo "Put TwsApi.jar and protobuf-java-*.jar from IB's TWS API into lib/ first. See lib/README.txt."
  exit 1
fi
# jar is not always on the PATH: find it in the JDK itself
JAR=jar
command -v jar >/dev/null 2>&1 || JAR="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p')/bin/jar"
mkdir -p build/classes dist/lib
javac --release 17 -encoding UTF-8 -cp "lib/*" -d build/classes src/putscreener/*.java || exit 1
printf 'Class-Path: lib/TwsApi.jar lib/protobuf-java-4.29.5.jar\n' > build/manifest.txt
"$JAR" --create --file dist/PutScreener.jar --manifest build/manifest.txt --main-class putscreener.PutScreener -C build/classes . || exit 1
cp lib/*.jar dist/lib/
cp PutScreener.bat putscreener.sh CompanyScore.bat companyscore.sh dist/
chmod +x dist/putscreener.sh dist/companyscore.sh
echo "Built dist/PutScreener.jar. Run: sh dist/putscreener.sh"
