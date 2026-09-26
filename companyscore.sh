#!/bin/sh
# Scores the companies in putscreener.properties from their SEC filings, with prices from IB (TWS must be
# running), and writes company_scores.csv. Keep PutScreener.jar and a lib folder next to this file.
cd "$(dirname "$0")" && exec java -cp "PutScreener.jar:lib/*" putscreener.CompanyScore "$@"
