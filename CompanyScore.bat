@echo off
rem Scores the companies in putscreener.properties from their SEC filings, with prices from IB (TWS must be
rem running), and writes company_scores.csv. Keep PutScreener.jar and a lib folder next to this file.
cd /d "%~dp0"
java -cp "PutScreener.jar;lib\*" putscreener.CompanyScore %*
pause
