#!/bin/sh
# Runs PutScreener. Keep PutScreener.jar and a lib folder (TwsApi.jar, protobuf-java-*.jar) next to this file.
cd "$(dirname "$0")" && exec java -cp "PutScreener.jar:lib/*" putscreener.PutScreener "$@"
