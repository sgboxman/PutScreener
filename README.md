# PutScreener

Screens weekly cash-secured puts using Interactive Brokers (IB) market data, and colours each put by
whether its premium looks rich compared with how the stock has actually been moving.

**Read-only: it never places an order. Not investment advice.** The numbers come from simple models
(see *Good to know*); check them, and the trade, yourself.

## What you need

- Java 17 or newer
- An IB account with TWS or IB Gateway running and logged in on the same computer
- Market data: US stocks (needed for price history), and US options (OPRA) or the *Delayed data* box
- IB's free TWS API download, for two jars (below)

## Set up

1. Put `PutScreener.jar` and the `.bat` and `.sh` scripts in one folder (from Releases, or build them, below).
2. Install IB's TWS API from <https://interactivebrokers.github.io> and copy two files into a `lib` folder next to
   the jar: `TwsApi.jar` (in `source/JavaClient/`) and `protobuf-java-4.29.5.jar` (in `source/JavaClient/jars/`).
3. In TWS: *File > Global Configuration > API > Settings*, tick **Enable ActiveX and Socket Clients** and note the
   socket port.
4. Run `PutScreener.bat` (Windows), or in a Mac/Linux terminal `sh putscreener.sh`. The first run writes
   `putscreener.properties` next to the jar: set your tickers, port and capital there, then press **Re-scan**.

Anything wrong (TWS not reachable, a missing subscription, a lost connection) is explained at the top of the box
under the table.

## Settings (`putscreener.properties`)

| Setting | Meaning | Default |
|---|---|---|
| `tickers` | Comma-separated; class shares as `BRK.B` | 10 large caps |
| `port` | TWS 7497 paper / 7496 live; IB Gateway 4002 / 4001 | 7497 |
| `client_id` | Any number no other API program uses | 4711 |
| `delta_min`, `delta_max` | Puts to consider, by absolute delta | 0.20, 0.40 |
| `max_spread_pct` | Widest bid-ask spread, as % of the mid | 20 |
| `min_iv_rv` | Least implied/realized move for MERIT | 1.15 |
| `rv_days` | Trading days of history for realized volatility | 20 |
| `gap_multiple` | Size of the gap the Tail column assumes, in implied moves | 3 |
| `dividends` | Ex-dividend weeks: `include` (priced on spot − dividend) or `skip` | include |
| `earnings_calendar` | `nasdaq` skips names reporting before expiry; `off` doesn't check | off |
| `mid_fill`, `delayed_data` | Start with these boxes ticked | false |
| `fill_at` | Price *Fill at mid* assumes: 0 bid, 0.5 mid, 1 ask | 0.5 |
| `capital` | Cash per position, for the Contracts column | 25000 |
| `sec_contact` | Your name and email, for company scores (below); sent only to the SEC | (empty) |

## Company scores (optional)

**Upgrading from 1.3 or earlier:** your `putscreener.properties` keeps working as it is. For company scores, add one
line to it with your name and email, for example `sec_contact = Jane Doe jane@example.com`.

`CompanyScore.bat` (or `sh companyscore.sh`) scores every company in `tickers` from 0 to 100, from its own 10-K and
10-Q filings (the SEC's free XBRL data, no key) and IB's price. TWS must be running. It takes about a minute and
writes `company_scores.csv` next to the settings, best first, plus a dated copy in `results/`.

Six measures, each scored 0–100 along straight lines through fixed points, then weighted: P/E (10), free-cash-flow
yield (20), net debt ÷ EBITDA (15), free-cash-flow growth over three years (20), return on invested capital (20) and
market cap (5). A measure the filings don't give is left out and the rest reweighted, but a total needs a P/E or an
FCF yield; the `notes` column says what is missing or approximated. Banks and insurers are shown but not scored: debt
and cash flow mean something else for them. The thresholds are a starting point, not tested against outcomes.

The weekly routine: run CompanyScore, delete the rows you don't want to screen that week (in Excel is fine; delete
whole rows), save, then run PutScreener. While `company_scores.csv` exists the screener scans only the names in it
and shows each one's score in the *Co. Score* column. It warns when the file is more than 8 days old. Delete the
file to scan all of `tickers` again. Running CompanyScore again overwrites your pruning (the dated copies don't
change).

The SEC's data can lag a new filing by weeks, and a few companies keep some figures under their own tags, which
its data leaves out; the `data_through` column and the notes show both.

## The window

- **Fill at mid**: price every put at the mid (`fill_at`) and let wide spreads through as MERIT@MID. Switches at once.
- **Delayed data**: IB's free delayed quotes, for accounts without OPRA.
- Outside 09:30–16:00 ET (13:00 on half days) live option quotes are empty, so the scan uses the last close's
  quotes; the label at the top says which.
- **Re-scan**: re-read the settings and scan again. **Capital**: recalculates Contracts at once (a changed
  `capital` in the settings file takes over at the next Re-scan).

| Column | Meaning |
|---|---|
| Co. Score | The company score from `company_scores.csv` (blank without one) |
| IV % | Implied volatility, annualised, from the mid |
| IV/RV | Move the option prices in ÷ move the stock has actually made, both to expiry |
| Edge@Mid $ | Per contract: premium at the mid − expected payout if the stock keeps moving as it has |
| Edge % | Edge ÷ cash secured (strike × 100) |
| Tail $ | Per contract: loss if the stock gaps down `gap_multiple` implied moves |
| Score | Edge ÷ Tail |
| Spread % | (ask − bid) ÷ mid |
| Prem % | Premium ÷ strike: the return if it expires worthless |
| Div $ | The dividend's share of the premium, in ex-dividend weeks |
| Contracts | Capital ÷ (strike × 100), rounded down |

Verdicts, tested in this order: **NO EDGE** (edge ≤ 0), **IV~RV** (IV/RV below `min_iv_rv`), **WIDE** (spread above
`max_spread_pct`; MERIT@MID with *Fill at mid*), otherwise **MERIT**.

## Good to know

- IB sends no option quotes 09:00–09:30 ET. Just after the close its closing quotes can be missing for a few names:
  press Re-scan.
- Edge assumes the stock keeps its recent volatility, with lognormal moves. Real markets have fatter tails, so read
  edge as optimistic.
- `earnings_calendar = nasdaq` reads Nasdaq's public web calendar, which is not an official API. Nasdaq's terms
  (nasdaq.com/legal) limit use to personal, non-commercial purposes; decide for yourself whether to turn it on.
- Dividends come from IB. With delayed data IB sends none, so ex-dividend weeks aren't recognised.
- Each scan writes a CSV to `results/` next to the settings file.
- The NYSE holiday and half-day lists in `PutScreener.java` run to 2028; the window warns when they need extending.

## Build

With the two jars in `lib/`: open the folder in NetBeans and *Clean and Build*, or run `build.bat` (Windows) or
`sh build.sh` (Mac/Linux) with JDK 17 or newer. Either puts a runnable `PutScreener.jar`, the scripts and `lib/`
in `dist/`. Built and tested with TWS API 10.45; older 9.x APIs won't compile.

NetBeans also makes `dist/PutScreener-standalone.jar`, one file with IB's API inside. It is for your own use only:
IB's license does not allow redistributing its API code.

## License

MIT, for PutScreener's own code (see `LICENSE`). IB's API is IB's, under IB's license.

Written by Andrew Boxerman with Claude Opus 5.5.
