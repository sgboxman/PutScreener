package putscreener;

import com.ib.client.*;

import java.awt.GraphicsEnvironment;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

/**
 * Weekly short-put screen. For each ticker in putscreener.properties:
 *   1. skip it if it reports earnings before this week's expiry (earnings_calendar = nasdaq); a
 *      name going ex-dividend (IB's next ex-date) is priced on spot - dividend (dividends =
 *      include) or skipped (= skip);
 *   2. pull price, daily history and this week's puts from IB;
 *   3. score each put between delta_min and delta_max.
 *
 * Score per put (per share):
 *   edge  = mid premium - expected payout if the stock moves like its recent realized
 *           volatility (lognormal, zero drift) instead of like the option's implied volatility
 *   tail  = loss if the stock gaps down gap_multiple implied expected moves, the move taken
 *           from the put nearest the money so every strike of a stock faces the same gap
 *   score = edge / tail
 *
 * The lognormal understates fat tails, so treat edge as optimistic.
 * Read-only: nothing here places an order.
 */
public class PutScreener {

    /** Shown in the window title, so screenshots can be told apart. */
    static final String VERSION = "1.3";
    static final String COPYRIGHT = "© 2026 - Andrew Boxerman and Claude Opus 5.5";

    static final ZoneId NY = ZoneId.of("America/New_York");
    static final String HOST = "127.0.0.1";

    // ---- config ----
    static List<String> tickers;
    static int port, clientId, mktDataType, rvDays;
    static double deltaMin, deltaMax, maxSpreadPct, minIvRv, gapMultiple, capital, fillAt;
    static boolean includeDividends, midFill, delayedData, nasdaqEarnings;

    static volatile EClientSocket client;
    static final W w = new W();
    static final AtomicInteger ids = new AtomicInteger(1000);

    /**
     * Per share, except score and the percentages. price is the premium the row assumes (the mid,
     * or the mid-fill price). dividend and divPart are 0 without an ex-date.
     */
    record Row(String sym, String expiry, double spot, double strike, double bid, double ask, double price,
               double iv, double delta, double ivRv, double edge, double edgeAtBid, double tail,
               double score, double spreadPct, double premPct, double dividend, double divPart,
               String divNote, String verdict) {}

    /** The next ex-dividend date and amount, from IB. Undeclared dividends are IB's projection. */
    record Div(LocalDate exDate, double amount) {
        @Override public String toString() { return day(exDate) + String.format(Locale.ROOT, " $%.2f", amount); }
    }

    /** MERIT first, then MERIT@MID, then best score. */
    static final Comparator<Row> ORDER = Comparator
            .comparingInt((Row r) -> r.verdict.equals("MERIT") ? 0 : r.verdict.equals("MERIT@MID") ? 1 : 2)
            .thenComparing(Comparator.comparingDouble((Row r) -> r.score).reversed());

    /** Cash-secured puts the capital covers. */
    static int contracts(double capital, double strike) { return (int) Math.floor(capital / (strike * 100)); }

    // ------------------------------------------------------------------ warnings

    /** A problem that stops the scan and that the user has to fix: no TWS, no data subscription. */
    static class ScanStop extends Exception {
        ScanStop(String message) { super(message); }
    }

    /** This scan's warnings, each shown once, at the top of the window's message box. */
    static final Set<String> warned = ConcurrentHashMap.newKeySet();
    static volatile ScreenerWindow window;
    static volatile Path createdConfig;       // a starter config written this run, to announce
    static double lastFileCapital = Double.NaN; // the file's capital at the last scan

    static void warn(String key, String text) {
        if (!warned.add(key)) return;
        System.out.println("WARNING  " + text);
        ScreenerWindow win = window;
        if (win != null) win.warn(text);
    }

    // ------------------------------------------------------------------ main

    /**
     * Args: [config path] [--nogui]. The window stays open when the screen finishes; close it to exit.
     * A double-clicked jar has no console, so in window mode a fatal error is shown in a dialog.
     */
    public static void main(String[] args) throws Exception {
        boolean gui = !Arrays.asList(args).contains("--nogui") && !GraphicsEnvironment.isHeadless();
        String given = Arrays.stream(args).filter(a -> !a.startsWith("--")).findFirst().orElse(null);
        // IB's API needs protobuf at run time, but compiles without it: check for it up front
        try {
            Class.forName("com.google.protobuf.MessageOrBuilder", false, PutScreener.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            String msg = "protobuf-java-*.jar is missing: put it in the lib folder next to PutScreener.jar, with"
                    + " TwsApi.jar (both come from IB's TWS API; see lib/README.txt).";
            if (gui) javax.swing.JOptionPane.showMessageDialog(null, msg, "Put Screener", javax.swing.JOptionPane.ERROR_MESSAGE);
            else System.out.println("FAILED  " + msg);
            System.exit(1);
        }
        if (!gui) {
            try {
                scan(given, null);
            } catch (Exception e) {
                System.out.println((e instanceof ScanStop ? "STOPPED  " : "FAILED  ") + e.getMessage());
                System.exit(1);
            }
            System.exit(0);
        }
        ScreenerWindow win;
        try {
            loadConfig(findConfig(given).toString());
            win = new ScreenerWindow(capital, tickers.size(), !inSession(ZonedDateTime.now(NY)), midFill, delayedData);
        } catch (Exception e) {
            e.printStackTrace();
            javax.swing.JOptionPane.showMessageDialog(null, e.getMessage() != null ? e.getMessage() : e.toString(),
                    "Put Screener", javax.swing.JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        window = win;
        win.onRescan(() -> new Thread(() -> scanInWindow(given, win), "rescan").start());
        scanInWindow(given, win);
    }

    /** One scan in the window; a problem is reported there and the Re-scan button comes back. */
    static void scanInWindow(String given, ScreenerWindow win) {
        try {
            scan(given, win);
        } catch (ScanStop e) {
            warn("stop", e.getMessage());
            win.status("Stopped - see the warning below");
        } catch (Throwable e) {                 // an Error too, e.g. a missing class: the window must say so
            e.printStackTrace();
            String msg = e instanceof NoClassDefFoundError
                    ? "a library is missing (" + e.getMessage() + "). Check the two jars in lib/ (see lib/README.txt)."
                    : e.getMessage() != null ? e.getMessage() : e.toString();
            warn("failed", "Scan failed: " + msg);
            win.status("Scan failed - see the warning below");
        } finally {
            disconnect();
            win.scanFinished();
        }
    }

    /** A regular-session moment: a trading day between 09:30 and 16:00 ET. */
    static boolean inSession(ZonedDateTime t) {
        return tradingDay(t.toLocalDate()) && !t.toLocalTime().isBefore(OPEN) && t.toLocalTime().isBefore(closeOn(t.toLocalDate()));
    }

    /** The most recent 16:00 close at or before t: what IB's frozen quotes show after hours. */
    static ZonedDateTime lastClose(ZonedDateTime t) {
        LocalDate d = t.toLocalDate();
        if (!tradingDay(d) || t.toLocalTime().isBefore(closeOn(d))) {
            do d = d.minusDays(1); while (!tradingDay(d));
        }
        return d.atTime(closeOn(d)).atZone(NY);
    }

    /**
     * The Friday of the week being screened. Once the week's last trading day has closed (Friday,
     * or Thursday when Friday is a holiday), its options are gone: screen next week's.
     */
    static LocalDate weekEnd(ZonedDateTime now) {
        LocalDate friday = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        LocalDate lastDay = friday;
        while (!tradingDay(lastDay)) lastDay = lastDay.minusDays(1);
        if (!now.isBefore(lastDay.atTime(closeOn(lastDay)).atZone(NY))) friday = friday.plusWeeks(1);
        return friday;
    }

    // ------------------------------------------------------------------ config

    static final String CONFIG = "putscreener.properties";

    /** Written next to the jar on the first run, when no config is found. */
    static final String STARTER_CONFIG = """
            # PutScreener settings. Lines starting with # are comments. Re-scan re-reads this file.

            # Tickers, separated by commas. Class shares as BRK.B
            tickers = AAPL, MSFT, GOOG, JNJ, KO, PG, V, WMT, PEP, MRK

            # TWS or IB Gateway API port: TWS 7497 paper / 7496 live, IB Gateway 4002 paper / 4001 live.
            port = 7497
            # Any number not used by another API program connected to the same TWS.
            client_id = 4711

            # Puts to consider, by absolute delta.
            delta_min = 0.20
            delta_max = 0.40

            # Verdict filters: bid-ask spread as % of the mid; implied move / realized move.
            max_spread_pct = 20
            min_iv_rv = 1.15

            # Realized volatility lookback in trading days. Tail: loss if the stock gaps down this many implied moves.
            rv_days = 20
            gap_multiple = 3

            # Ex-dividend weeks: include (priced on spot - dividend) or skip.
            dividends = include

            # Earnings weeks: nasdaq (skip names reporting before expiry, from Nasdaq's public calendar; see README) or off.
            earnings_calendar = off

            # Start with these boxes ticked.
            mid_fill = false
            delayed_data = false
            # The price "Fill at mid" assumes: 0 = bid, 0.5 = mid, 1 = ask.
            fill_at = 0.5

            # Cash per position, for the Contracts column (cash-secured puts).
            capital = 25000
            """;

    /**
     * The config: the path given, else the first putscreener.properties in the current folder, the
     * jar's folder, or the folder above it (so a jar run from dist finds the project's config).
     * None anywhere: a starter config is written next to the jar (or in the current folder when
     * running from classes; or the project folder when the jar is in a build's dist folder, which
     * a clean build deletes) and announced as a warning.
     */
    static Path findConfig(String given) throws IOException {
        if (given != null) {
            Path p = Paths.get(given).toAbsolutePath();
            if (!Files.isRegularFile(p)) throw new FileNotFoundException("Config not found: " + p);
            return p;
        }
        Path cwd = Paths.get("").toAbsolutePath();
        List<Path> dirs = new ArrayList<>(List.of(cwd));
        Path home = cwd;
        try {
            Path code = Paths.get(PutScreener.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isDirectory(code)) {
                home = code.getParent();
                dirs.add(home);
                Path up = home.getParent();
                if (up != null) {
                    dirs.add(up);
                    if (home.getFileName().toString().equals("dist") && Files.exists(up.resolve("build.xml"))) home = up;
                }
            }
        } catch (Exception e) {
            // no code location: the current folder is all there is
        }
        for (Path d : dirs) if (Files.isRegularFile(d.resolve(CONFIG))) return d.resolve(CONFIG);
        Path p = home.resolve(CONFIG);
        try {
            Files.writeString(p, STARTER_CONFIG);
        } catch (IOException e) {
            throw new IOException("No " + CONFIG + " was found and a starter one can't be written in " + home
                    + " (" + e.getClass().getSimpleName() + "). Put one there yourself, or run from a folder you can write to.");
        }
        createdConfig = p;
        return p;
    }

    // ------------------------------------------------------------------ one scan

    /**
     * One full screen. The config is re-read every time, so a Re-scan picks up edits. After hours
     * (the window's box; on the console, outside 09:30-16:00) the quotes are IB's frozen ones
     * from the last close, so time and the spot are taken at that close too.
     */
    static void scan(String given, ScreenerWindow win) throws Exception {
        warned.clear();
        Path cfg = findConfig(given);
        loadConfig(cfg.toString());
        System.out.println("Config: " + cfg);
        long t0 = System.currentTimeMillis();

        ZonedDateTime now = ZonedDateTime.now(NY);
        LocalDate today = now.toLocalDate();
        LocalDate friday = weekEnd(now);

        boolean afterHours = win != null ? win.afterHours() : !inSession(now);
        boolean delayed = win != null ? win.delayed() : delayedData;
        stockDataSeen = false;
        optionDataSeen = false;
        if (win != null) {
            // Outside the session live quotes are empty: After Hours goes on by itself
            if (!afterHours && !inSession(now)) { afterHours = true; win.setAfterHours(true); }
            // The capital box sizes Contracts, unless the file's capital was changed since the last scan
            if (capital != lastFileCapital) { lastFileCapital = capital; win.setCapital(capital); }
            else capital = win.capital();
            win.scanStarting(tickers.size(), fillAt);
        }
        if (createdConfig != null) {
            warn("starter", "No settings file was found, so a starter one was written: " + createdConfig
                    + ". Edit tickers, port and capital there, then Re-scan.");
            createdConfig = null;
        }
        if (afterHours && inSession(now)) {
            // IB serves live quotes during the session whatever the setting, so time them live
            afterHours = false;
            warn("ah-in-session", "After Hours is ticked during market hours: IB sends live quotes, so live timing is used.");
        }
        LocalTime t = now.toLocalTime();
        if (tradingDay(today) && !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(OPEN))
            warn("preopen", "IB sends no option quotes between 09:00 and 09:30 ET. Re-scan after the open.");
        if (delayed && !afterHours && t.isBefore(OPEN.plusMinutes(15)))
            warn("delayed-open", "Delayed quotes are 15 minutes old: until 09:45 ET they are from before the open,"
                    + " when IB has no option quotes.");
        if (today.getYear() > LAST_HOLIDAY_YEAR)
            warn("holidays", "The NYSE holiday list in PutScreener.java ends in " + LAST_HOLIDAY_YEAR + ": add this year's.");
        mktDataType = delayed ? (afterHours ? 4 : 3) : (afterHours ? 2 : 1);
        ZonedDateTime quoteTime = afterHours ? lastClose(now) : now;

        if (win != null) win.status("Connecting to TWS on port " + port + "...");
        connect();
        try {
            Map<String, String> earnings = null;
            if (nasdaqEarnings) {
                if (win != null) win.status("Reading the earnings calendar...");
                earnings = fetchEarnings(today, friday, now);
                if (earnings == null)
                    warn("earnings-fail", "Couldn't read Nasdaq's earnings calendar, so earnings weeks are NOT excluded."
                            + " Check report dates yourself.");
            } else {
                warn("earnings-off", "Earnings dates are not checked (earnings_calendar = off). Check report dates"
                        + " yourself before selling.");
            }
            if (delayed)
                warn("div-delayed", "Delayed data: IB sends no dividend data, so ex-dividend weeks are not recognised."
                        + " Check ex-dates yourself.");

            List<Row> rows = new ArrayList<>();
            List<Row> fillRows = new ArrayList<>();       // the same puts priced at the assumed fill
            List<String> skipped = new ArrayList<>();
            boolean partial = false;
            for (int i = 0; i < tickers.size(); i++) {
                if (w.lost || !client.isConnected()) {
                    partial = true;
                    warn("lost", "Lost the connection to TWS after " + i + " of " + tickers.size()
                            + " names, so this scan is PARTIAL. Re-scan when TWS is back.");
                    break;
                }
                String sym = tickers.get(i);
                int rowsBefore = rows.size(), fillBefore = fillRows.size(), skippedBefore = skipped.size();
                if (win != null) win.status("Screening " + sym + "  (" + (i + 1) + " of " + tickers.size() + ")");
                try {
                    String report = earnings == null ? null : earningsFor(earnings, sym);
                    if (report != null) skipped.add(sym + "  earnings " + report);
                    else screen(sym, now, quoteTime, afterHours, delayed, friday, rows, fillRows, skipped);
                } catch (ScanStop e) {
                    throw e;
                } catch (Exception e) {
                    skipped.add(sym + "  error: " + e.getMessage());
                }
                if (w.lost || !client.isConnected()) {
                    // The connection went while this name was in progress: whatever it got is not its answer
                    rows.subList(rowsBefore, rows.size()).clear();
                    fillRows.subList(fillBefore, fillRows.size()).clear();
                    skipped.subList(skippedBefore, skipped.size()).clear();
                    partial = true;
                    warn("lost", "Lost the connection to TWS after " + i + " of " + tickers.size()
                            + " names, so this scan is PARTIAL. Re-scan when TWS is back.");
                    break;
                }
                if (win != null) {
                    win.addRows(rows.subList(rowsBefore, rows.size()), fillRows.subList(fillBefore, fillRows.size()));
                    for (String s : skipped.subList(skippedBefore, skipped.size())) win.note(s);
                    win.progress(i + 1);
                }
            }

            rows.sort(ORDER);
            fillRows.sort(ORDER);
            Map<String, Row> atFill = new HashMap<>();
            for (Row f : fillRows) atFill.put(f.sym + "|" + f.strike, f);
            print(rows, atFill, skipped, now);
            Path csv = writeCsv(rows, atFill, now, cfg.getParent().resolve("results"), partial);

            if (win != null) {
                long merit = rows.stream().filter(r -> r.verdict.equals("MERIT")).count();
                long atMid = fillRows.stream().filter(r -> r.verdict.equals("MERIT@MID")).count();
                String asOf = afterHours ? "the " + quoteTime.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)) + " 16:00 close"
                        : now.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)) + " ET";
                // Warnings first: the end of a long status line can disappear behind the controls
                String warnings = warned.isEmpty() ? "" : warned.size() + (warned.size() == 1 ? " WARNING" : " WARNINGS") + " below.  ";
                win.status(String.format("%s%s in %d s: %d puts, %d MERIT, %d more MERIT@MID.  Quotes as of %s%s.  Saved %s",
                        warnings, partial ? "PARTIAL" : "Done", (System.currentTimeMillis() - t0) / 1000, rows.size(), merit, atMid,
                        asOf, delayed ? " (delayed)" : "", csv.getFileName()));
            }
        } finally {
            disconnect();
        }
    }

    /** Nasdaq writes class shares with a dot; IB with a space. */
    static String earningsFor(Map<String, String> earnings, String sym) {
        for (String k : List.of(sym, sym.replace(' ', '.'), sym.replace(' ', '/'))) {
            String v = earnings.get(k);
            if (v != null) return v;
        }
        return null;
    }

    // ------------------------------------------------------------------ one name

    /**
     * quoteTime is when the option quotes are from: now when live, the last 16:00 close after
     * hours. Time to expiry, days left and the ex-dividend window all run from it.
     */
    static void screen(String sym, ZonedDateTime now, ZonedDateTime quoteTime, boolean afterHours, boolean delayed,
                       LocalDate friday, List<Row> rows, List<Row> fillRows, List<String> skipped) throws Exception {
        // Stock contract
        Contract stk = new Contract();
        stk.symbol(sym); stk.secType("STK"); stk.exchange("SMART"); stk.currency("USD");
        List<ContractDetails> sd = contractDetails(stk);
        if (sd.isEmpty()) { skipped.add(sym + "  IB doesn't recognise this ticker"); return; }
        Contract s = sd.get(0).contract();
        s.exchange("SMART");

        // Realized volatility: stdev of daily log returns, close to close. During the session
        // IB's last daily bar is today's unfinished one; its partial return would bias the stdev.
        int hq = ids.incrementAndGet();
        List<Bar> bars = history(s, hq);
        if (bars.isEmpty()) checkDataError(sym, w.errCode(hq), w.errMsg(hq), false);
        else stockDataSeen = true;
        String todayStr = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!bars.isEmpty() && bars.get(bars.size() - 1).time().startsWith(todayStr)
                && now.toLocalTime().isBefore(closeOn(now.toLocalDate()))) bars = bars.subList(0, bars.size() - 1);
        if (bars.size() < rvDays + 1) { skipped.add(sym + "  only " + bars.size() + " daily bars"); return; }
        double dailySd = dailyStdev(bars, rvDays);

        // Price. After hours the option quotes are the close's, but the stock keeps trading
        // pre- and post-market, so its quote would not match them: take the last close instead.
        double spot;
        Quote sq = null;
        if (afterHours) {
            spot = bars.get(bars.size() - 1).close();
        } else {
            sq = snapshots(List.of(s), true).get(0);
            if (!(sq.price() > 0)) checkDataError(sym, sq.err, sq.errMsg, false);
            spot = sq.price();
        }
        if (!(spot > 0)) { skipped.add(sym + "  no stock price"); return; }

        // This week's expiry and the trading class that carries it
        int rq = ids.incrementAndGet();
        w.open(rq);
        client.reqSecDefOptParams(rq, sym, "", "STK", s.conid());
        w.await(rq, 15);
        String tc = null, mult = "100", expiry = null;
        Set<Double> strikes = Set.of();
        for (W.SecDef d : w.secdefs.getOrDefault(rq, List.of())) {
            if (!d.exchange.equals("SMART")) continue;
            LocalDate from = now.toLocalTime().isBefore(closeOn(now.toLocalDate())) ? now.toLocalDate() : now.toLocalDate().plusDays(1);
            String e = latestExpiry(d.expirations, from, friday);
            if (e == null) continue;
            // Prefer the class named after the stock; adjusted classes carry a digit
            if (tc == null || d.tradingClass.equals(sym)) {
                tc = d.tradingClass; mult = d.multiplier; expiry = e; strikes = d.strikes;
            }
        }
        if (expiry == null) { skipped.add(sym + "  no expiry this week"); return; }

        // Horizon: implied over calendar time, realized over the trading days left
        LocalDate exp = LocalDate.parse(expiry, DateTimeFormatter.BASIC_ISO_DATE);
        ZonedDateTime expClose = exp.atTime(closeOn(exp)).atZone(NY);
        double tCal = Duration.between(quoteTime, expClose).toMinutes() / (365.0 * 24 * 60);
        if (tCal <= 0) { skipped.add(sym + "  expiry " + expiry + " already closed"); return; }
        double sigReal = dailySd * Math.sqrt(daysLeft(quoteTime, exp));

        // A dividend going ex after the quotes' day and by expiry: the stock will drop by it, so
        // the put is really written on spot - dividend. An ex-date after this expiry does not
        // touch it. Live, an ex-date today is in the price once trading starts, but before the
        // open the spot may still be the pre-ex close while the puts already price the drop, so
        // wait for 09:30. After hours spot and puts are both the last close, so today counts.
        // IB sends no dividend tick on delayed data (warned once per scan).
        Div div = delayed ? null : nextDividend(s);
        LocalDate quoteDay = quoteTime.toLocalDate();
        if (!afterHours && div != null && div.exDate.equals(quoteDay) && now.toLocalTime().isBefore(OPEN)) {
            skipped.add(sym + "  goes ex-dividend today " + div + " - run again after 09:30, or tick After Hours");
            return;
        }
        boolean exThisWeek = div != null && div.exDate.isAfter(quoteDay) && !div.exDate.isAfter(exp);
        if (exThisWeek && !includeDividends) { skipped.add(sym + "  ex-dividend " + div); return; }
        double dividend = exThisWeek ? div.amount : 0;
        String divNote = exThisWeek ? "ex " + div : "";

        // Put strikes in the band the delta range can reach: |delta| = delta_min sits near
        // K = S exp(-z sigma), z = N^-1(1 - delta_min). Realized stands in for implied, which is
        // not known yet, with room for implied up to 2.5x realized.
        // The strikes come from the expiry request above, not a chain lookup: IB rations chain
        // lookups to ~4 s each over a full run. The list covers every expiry of the class, so a
        // strike this week lacks just comes back unquoted, the same as an untraded one. The puts
        // are built from fields, no conId.
        double lo = Math.min((spot - dividend) * Math.exp(-2.5 * ninv(1 - deltaMin) * sigReal), spot * 0.98);
        List<Contract> puts = new ArrayList<>();
        for (double k : strikes) {
            if (k > spot || k < lo) continue;
            Contract c = new Contract();
            c.symbol(sym); c.secType("OPT"); c.exchange("SMART"); c.currency("USD");
            c.lastTradeDateOrContractMonth(expiry); c.right("P"); c.strike(k); c.tradingClass(tc); c.multiplier(mult);
            puts.add(c);
        }
        puts.sort(Comparator.comparingDouble((Contract c) -> c.strike()).reversed());
        if (puts.size() > 40) puts = puts.subList(0, 40);   // room for the extra strikes of other expiries
        if (puts.isEmpty()) { skipped.add(sym + "  no put strikes found for " + expiry); return; }

        List<Quote> qs = snapshots(puts, false);

        // Delayed data asked for, but IB answers with the account's live options feed: it does
        // that when the account has an OPRA subscription. After hours that feed is empty, and IB
        // does not fall back to the close for delayed requests, so ask for the frozen close.
        if (delayed && qs.stream().anyMatch(q -> q.dataType == 1 && (q.gotBid || q.bid > 0)))
            warn("live-sub", "Delayed data is ticked, but this account has live options data, so IB sends that"
                    + " instead. You can untick Delayed data.");
        // Only when IB actually announced its live feed: an account without OPRA never gets frozen quotes
        if (delayed && afterHours && mktDataType == 4 && qs.stream().noneMatch(q -> q.bid > 0 && q.ask > 0)
                && qs.stream().anyMatch(q -> q.saidLive) && qs.stream().allMatch(q -> q.dataType == 1)) {
            mktDataType = 2;
            client.reqMarketDataType(2);
            qs = snapshots(puts, false);
        }
        if (qs.stream().anyMatch(q -> q.bid > 0 && q.ask > 0)) optionDataSeen = true;
        else for (Quote q : qs) if (q.err != 0) checkDataError(sym, q.err, q.errMsg, true);
        // Some quotes refused, some not: the missing strikes would otherwise vanish without a word
        for (Quote q : qs) if (q.err == 101 || q.err == 10197) {
            warn("partial" + q.err, "IB " + q.err + ": some option quotes were refused ("
                    + (q.err == 101 ? "too many market data lines in use: close some TWS watchlists or charts"
                                    : "another session is using your market data")
                    + "), so strikes are missing. Fix it, then Re-scan.");
            break;
        }

        // Delayed data: a live stock subscription still sends a live stock price, while the
        // options arrive ~15 minutes old
        if (sq != null && !sq.delayed && qs.stream().anyMatch(q -> q.delayed))
            warn("mixed", "Delayed data: option quotes are ~15 minutes old but the stock price is live. In a fast"
                    + " market IV/RV and edge can be off. After hours this doesn't apply.");

        // One implied move per stock for the tail, from the quoted put nearest the money
        // (puts run highest strike first). Skew would otherwise give each strike its own gap.
        double sigAtm = Double.NaN;
        for (int i = 0; i < puts.size() && Double.isNaN(sigAtm); i++) {
            Quote q = qs.get(i);
            if (q.bid > 0 && q.ask > 0) sigAtm = impliedSig(spot - dividend, puts.get(i).strike(), (q.bid + q.ask) / 2);
        }

        int scored = 0;
        Row above = null, below = null;   // nearest quoted strikes just outside the delta range
        for (int i = 0; i < puts.size(); i++) {
            double k = puts.get(i).strike();
            // Scored both ways, so the window's mid-fill box switches without a re-scan
            Row f = score(sym, expiry, spot, dividend, divNote, k, qs.get(i), tCal, sigReal, sigAtm, true);
            if (f != null && -f.delta >= deltaMin && -f.delta <= deltaMax) fillRows.add(f);
            Row r = score(sym, expiry, spot, dividend, divNote, k, qs.get(i), tCal, sigReal, sigAtm, false);
            if (r == null) continue;
            if (-r.delta > deltaMax) { if (above == null || -r.delta < -above.delta) above = r; continue; }
            if (-r.delta < deltaMin) { if (below == null || -r.delta > -below.delta) below = r; continue; }
            scored++;
            rows.add(r);
        }
        if (scored == 0) {
            String range = String.format(Locale.ROOT, "%.2f-%.2f", deltaMin, deltaMax);
            if (above == null && below == null) skipped.add(sym + "  no quoted puts near the money");
            else skipped.add(sym + "  no strike with delta " + range + "; nearest:"
                    + (above != null ? String.format(Locale.ROOT, " %.2f at %.2f", above.strike, -above.delta) : "")
                    + (above != null && below != null ? "," : "")
                    + (below != null ? String.format(Locale.ROOT, " %.2f at %.2f", below.strike, -below.delta) : ""));
        }
    }

    /** This scan has already had stock (history) or option data from IB: a data error is then one ticker's. */
    static volatile boolean stockDataSeen, optionDataSeen;

    /**
     * IB's reason for getting no data. Before any data has arrived it is one every name will hit, so
     * stop the scan with it rather than list the same empty result for every ticker; once data has
     * arrived it is this ticker's (a listing the account has no permission for), so skip just it.
     * Codes that concern one contract only (200; 162 without a permissions message) are left alone.
     */
    static void checkDataError(String sym, int code, String msg, boolean options) throws ScanStop {
        String m = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        boolean noSubscription = code == 354 || code == 10089 || code == 10168 || code == 10186
                || (code == 162 && (m.contains("permission") || m.contains("subscri")));
        if (!noSubscription && code != 10197 && code != 101) return;
        if (options ? optionDataSeen : stockDataSeen)
            throw new IllegalStateException("no " + (options ? "option" : "stock") + " data for this ticker (IB " + code + ")");
        String at = "At " + sym + ": ";
        if (noSubscription && options)
            throw new ScanStop(at + "no US options data (IB " + code + "). Subscribe to OPRA in IB's Client Portal, or"
                    + " tick Delayed data. Paper account: turn on market data sharing with it (can take a day).");
        if (noSubscription)
            throw new ScanStop(at + "no US stock data (IB " + code + "). IB needs a US stock subscription for price"
                    + " history, even with Delayed data. Paper account: turn on market data sharing with it (can take a day).");
        if (code == 10197)
            throw new ScanStop("IB 10197: another session is using your market data (the IBKR mobile or web app, or a"
                    + " second TWS). Log out of it, then Re-scan.");
        throw new ScanStop("IB 101: too many market data lines in use. Close some TWS watchlists or charts, then Re-scan.");
    }

    /**
     * Implied volatility and delta are solved here from the mid, on the same clock and spot as
     * the edge, so the columns cannot contradict each other (IB's model values use IB's clock).
     *
     * With a dividend before expiry everything is priced on spot - dividend (the escrowed-dividend
     * model at zero rates): the dividend is then fair compensation for the drop, not edge.
     * divPart is how much of the premium the dividend accounts for, about |delta| x dividend.
     */
    static Row score(String sym, String expiry, double spot, double dividend, String divNote, double k, Quote q,
                     double tCal, double sigReal, double sigAtm, boolean atFill) {
        if (!(q.bid > 0 && q.ask > 0)) return null;
        double mid = (q.bid + q.ask) / 2;
        // The premium assumed: the mid, or with the mid-fill box bid + fill_at x spread (0.5 = mid)
        double price = atFill ? q.bid + fillAt * (q.ask - q.bid) : mid;
        double s = spot - dividend;
        double sigImp = impliedSig(s, k, price);
        if (Double.isNaN(sigImp)) return null;
        double d1 = (Math.log(s / k) + sigImp * sigImp / 2) / sigImp;
        double delta = -ncdf(-d1);

        double payout = putZeroRate(s, k, sigReal);
        double edge = price - payout;
        double edgeAtBid = q.bid - payout;
        double gapPrice = s * (1 - gapMultiple * (Double.isNaN(sigAtm) ? sigImp : sigAtm));
        double tail = Math.max(k - gapPrice - price, 0.01);
        double spreadPct = (q.ask - q.bid) / mid * 100;
        double ivRv = sigImp / sigReal;
        double divPart = dividend > 0 ? price - putZeroRate(spot, k, sigImp) : 0;

        // With the mid-fill box a wide spread no longer fails the put, but the verdict says so
        String verdict;
        if (edge <= 0) verdict = "NO EDGE";
        else if (ivRv < minIvRv) verdict = "IV~RV";
        else if (spreadPct > maxSpreadPct) verdict = atFill ? "MERIT@MID" : "WIDE";
        else verdict = "MERIT";

        return new Row(sym, expiry, spot, k, q.bid, q.ask, price, sigImp / Math.sqrt(tCal), delta, ivRv, edge,
                edgeAtBid, tail, edge / tail, spreadPct, price / k * 100, dividend, divPart, divNote, verdict);
    }

    /** Inverse standard normal, by bisection on ncdf. */
    static double ninv(double p) {
        double lo = -8, hi = 8;
        for (int i = 0; i < 60; i++) {
            double m = (lo + hi) / 2;
            if (ncdf(m) < p) lo = m; else hi = m;
        }
        return (lo + hi) / 2;
    }

    /** Horizon stdev that prices the put at the given premium; NaN if below intrinsic. */
    static double impliedSig(double s, double k, double premium) {
        if (premium <= Math.max(k - s, 0)) return Double.NaN;
        double lo = 1e-5, hi = 2.0;
        if (putZeroRate(s, k, hi) < premium) return Double.NaN;
        for (int i = 0; i < 100; i++) {
            double m = (lo + hi) / 2;
            if (putZeroRate(s, k, m) < premium) lo = m; else hi = m;
        }
        return (lo + hi) / 2;
    }

    // ------------------------------------------------------------------ math

    /** Put value with zero rate and zero drift; sig is the stdev of the log return to expiry. */
    static double putZeroRate(double s, double k, double sig) {
        if (sig <= 0) return Math.max(k - s, 0);
        double d1 = (Math.log(s / k) + sig * sig / 2) / sig;
        double d2 = d1 - sig;
        return k * ncdf(-d2) - s * ncdf(-d1);
    }

    static double ncdf(double x) {
        // Abramowitz-Stegun 7.1.26 on erf; error under 1e-7
        double z = Math.abs(x) / Math.sqrt(2);
        double t = 1 / (1 + 0.3275911 * z);
        double erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
                + 0.254829592) * t * Math.exp(-z * z);
        return x >= 0 ? 0.5 * (1 + erf) : 0.5 * (1 - erf);
    }

    static double dailyStdev(List<Bar> bars, int n) {
        int end = bars.size() - 1;
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = Math.log(bars.get(end - i).close() / bars.get(end - i - 1).close());
        double m = Arrays.stream(r).average().orElse(0);
        double v = 0;
        for (double x : r) v += (x - m) * (x - m);
        return Math.sqrt(v / (n - 1));
    }

    static final LocalTime OPEN = LocalTime.of(9, 30), CLOSE = LocalTime.of(16, 0);

    /** NYSE full-day closures. Extend each year (a warning appears once the list runs out). */
    static final int LAST_HOLIDAY_YEAR = 2028;
    static final Set<LocalDate> NYSE_HOLIDAYS = Set.of(
            LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 25),
            LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 18), LocalDate.of(2027, 2, 15), LocalDate.of(2027, 3, 26),
            LocalDate.of(2027, 5, 31), LocalDate.of(2027, 6, 18), LocalDate.of(2027, 7, 5), LocalDate.of(2027, 9, 6),
            LocalDate.of(2027, 11, 25), LocalDate.of(2027, 12, 24),
            LocalDate.of(2028, 1, 17), LocalDate.of(2028, 2, 21), LocalDate.of(2028, 4, 14), LocalDate.of(2028, 5, 29),
            LocalDate.of(2028, 6, 19), LocalDate.of(2028, 7, 4), LocalDate.of(2028, 9, 4), LocalDate.of(2028, 11, 23),
            LocalDate.of(2028, 12, 25));

    /** NYSE 13:00 early closes (equity options stop at 13:00 too). Extend with the holiday list. */
    static final Set<LocalDate> NYSE_EARLY_CLOSES = Set.of(
            LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 24),
            LocalDate.of(2027, 11, 26),
            LocalDate.of(2028, 7, 3), LocalDate.of(2028, 11, 24));

    static LocalTime closeOn(LocalDate d) { return NYSE_EARLY_CLOSES.contains(d) ? LocalTime.of(13, 0) : CLOSE; }

    static boolean tradingDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY
                && !NYSE_HOLIDAYS.contains(d);
    }

    /**
     * Close-to-close trading days left through expiry. Today counts in full before the open,
     * by the share of the session left during it, and not at all after the close.
     */
    static double daysLeft(ZonedDateTime now, LocalDate exp) {
        LocalDate today = now.toLocalDate();
        LocalTime t = now.toLocalTime();
        double n = 0;
        for (LocalDate d = today; !d.isAfter(exp); d = d.plusDays(1)) {
            if (!tradingDay(d)) continue;
            if (!d.equals(today)) n++;
            else if (t.isBefore(closeOn(d))) {
                double session = Duration.between(OPEN, closeOn(d)).toMinutes();
                n += t.isAfter(OPEN) ? Duration.between(t, closeOn(d)).toMinutes() / session : 1.0;
            }
        }
        return Math.max(n, 1.0 / 390);
    }

    static String latestExpiry(Set<String> exps, LocalDate from, LocalDate to) {
        String best = null;
        for (String e : exps) {
            LocalDate d = LocalDate.parse(e, DateTimeFormatter.BASIC_ISO_DATE);
            if (!d.isBefore(from) && !d.isAfter(to) && (best == null || e.compareTo(best) > 0)) best = e;
        }
        return best;
    }

    // ------------------------------------------------------------------ earnings

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** One day of Nasdaq's public earnings calendar, requested without waiting; nasdaqBody checks the reply. */
    static CompletableFuture<HttpResponse<String>> nasdaqDay(String kind, LocalDate d) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.nasdaq.com/api/calendar/" + kind + "?date=" + d))
                .header("User-Agent", "PutScreener/" + VERSION).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20)).build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Throws unless it is a real calendar reply, so a blocked or error page can never read as
     * "nobody on the list".
     */
    static String nasdaqBody(String kind, LocalDate d, CompletableFuture<HttpResponse<String>> f) throws Exception {
        HttpResponse<String> resp = f.get(30, TimeUnit.SECONDS);
        String body = resp.body();
        if (resp.statusCode() != 200 || !body.contains("\"rCode\":200"))
            throw new IOException(kind + " " + d + ": HTTP " + resp.statusCode());
        return body;
    }

    /** Data rows in a calendar reply: every "symbol" key except the header's. */
    static int symbolRows(String body) {
        int n = 0;
        for (int i = body.indexOf("\"symbol\":\""); i >= 0; i = body.indexOf("\"symbol\":\"", i + 1))
            if (!body.startsWith("\"symbol\":\"Symbol\"", i)) n++;
        return n;
    }

    static String day(LocalDate d) { return d.getDayOfWeek().toString().substring(0, 3) + " " + d; }

    /** Symbol -> "date time" for reports between today and friday; null if Nasdaq can't be read. */
    static Map<String, String> fetchEarnings(LocalDate from, LocalDate to, ZonedDateTime now) {
        Map<String, String> out = new HashMap<>();
        Pattern row = Pattern.compile("\"time\":\"([^\"]*)\",\"symbol\":\"([^\"]+)\"");
        try {
            // All the week's days at once
            Map<LocalDate, CompletableFuture<HttpResponse<String>>> replies = new LinkedHashMap<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
                if (tradingDay(d)) replies.put(d, nasdaqDay("earnings", d));
            for (LocalDate d : replies.keySet()) {
                String body = nasdaqBody("earnings", d, replies.get(d));
                Matcher m = row.matcher(body);
                int matched = 0;
                while (m.find()) {
                    if (m.group(2).equals("Symbol")) continue;
                    matched++;
                    String time = m.group(1).replace("time-", "");
                    // A pre-market report today is already in the price once the market is open
                    if (d.equals(from) && time.equals("pre-market") && now.toLocalTime().isAfter(OPEN)) continue;
                    out.put(m.group(2), day(d) + " " + time);
                }
                if (matched != symbolRows(body))
                    throw new IOException("earnings " + d + ": layout changed, read " + matched + " of " + symbolRows(body));
            }
            return out;
        } catch (Exception e) {
            System.out.println("Earnings calendar failed: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------------ IB plumbing

    /**
     * The stock's next ex-date and amount from IB: generic tick 456 answers with tick 59,
     * "past12,next12,nextDate,nextAmount", within ~50 ms. Generic ticks need a streaming request,
     * cancelled as soon as the tick is in. Null when IB sends no dividend.
     */
    static Div nextDividend(Contract s) throws InterruptedException {
        int id = ids.incrementAndGet();
        w.open(id);
        pace();
        client.reqMktData(id, s, "456", false, false, null);
        w.awaitMs(id, 2_000);
        pace();
        client.cancelMktData(id);
        String v = w.dividends.remove(id);
        if (v == null && w.errCode(id) != 0)
            warn("div-err", "IB " + w.errCode(id) + " on a dividend request (" + s.symbol() + "): names may go"
                    + " ex-dividend unnoticed this scan. Check ex-dates yourself.");
        if (v == null) return null;
        String[] f = v.split(",");
        try {
            if (f.length < 4 || f[2].isBlank() || f[3].isBlank()) return null;
            return new Div(LocalDate.parse(f[2].trim(), DateTimeFormatter.BASIC_ISO_DATE), Double.parseDouble(f[3].trim()));
        } catch (RuntimeException e) {
            System.out.println(s.symbol() + ": unreadable dividend tick '" + v + "'");
            return null;
        }
    }

    static String noTws() {
        return "Can't reach TWS or IB Gateway at " + HOST + ":" + port + ". Start it and log in; in TWS turn on"
                + " Global Configuration > API > Settings > Enable ActiveX and Socket Clients; and check the port in "
                + CONFIG + ": TWS 7497 paper / 7496 live, IB Gateway 4002 paper / 4001 live.";
    }

    /**
     * A fresh connection per scan: each scan disconnects when it is done. The socket is opened
     * here, with timeouts, because IB's own eConnect(host, port, id) waits forever when TWS is
     * showing its "accept incoming connection?" prompt or is still logging in.
     */
    static void connect() throws Exception {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(HOST, port), 5_000);
        } catch (IOException e) {
            sock.close();
            throw new ScanStop(noTws());
        }
        sock.setSoTimeout(15_000);
        EJavaSignal signal = new EJavaSignal();
        EClientSocket c = new EClientSocket(w, signal);
        w.reset();
        client = c;
        String noAnswer = "TWS on port " + port + " didn't answer. If it is showing \"Accept incoming connection?\","
                + " click Yes (or add " + HOST + " to Trusted IPs in its API settings); make sure it is logged in; then Re-scan.";
        try {
            c.eConnect(sock, clientId);
        } catch (IOException e) {
            try { sock.close(); } catch (IOException x) { /* already closed */ }
            throw new ScanStop(noAnswer);
        }
        if (!c.isConnected()) throw new ScanStop(noAnswer);
        sock.setSoTimeout(0);
        EReader reader = new EReader(c, signal);
        reader.start();
        Thread t = new Thread(() -> {
            while (c.isConnected()) {       // this connection's own client, not the next scan's
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception e) { System.out.println("reader: " + e); }
            }
        }, "ib-reader");
        t.setDaemon(true);
        t.start();
        boolean ok = w.connected.await(10, TimeUnit.SECONDS);
        // TWS refuses a client id in use with 326 and then closes; the close can overtake the 326
        // on its way to us, so give a queued refusal a moment to land before judging
        if (w.lost || !c.isConnected()) Thread.sleep(300);
        if (w.connFail != null) { c.eDisconnect(); throw new ScanStop(w.connFail); }
        if (!ok || w.lost || !c.isConnected()) { c.eDisconnect(); throw new ScanStop(noAnswer); }
        c.reqMarketDataType(mktDataType);
    }

    static void disconnect() {
        EClientSocket c = client;
        if (c != null && c.isConnected()) c.eDisconnect();
    }

    static List<ContractDetails> contractDetails(Contract c) throws InterruptedException {
        int id = ids.incrementAndGet();
        w.open(id);
        client.reqContractDetails(id, c);
        w.await(id, 20);
        return w.details.getOrDefault(id, List.of());
    }

    static List<Bar> history(Contract c, int id) throws InterruptedException {
        w.open(id);
        client.reqHistoricalData(id, c, "", "6 M", "1 day", "TRADES", 1, 1, false, null);
        w.await(id, 30);
        return w.bars.getOrDefault(id, List.of());
    }

    /**
     * Snapshot quotes, 25 at a time. A quote is done as soon as its bid and ask are in, and its
     * line is cancelled straight away, instead of waiting ~11 s for IB's snapshot end (which
     * waits for fields this tool never uses). Lines are held for about a second, which is kinder
     * to other programs using the same account's market data lines. Anything not in by the
     * deadline counts as unquoted.
     */
    static List<Quote> snapshots(List<Contract> cs, boolean stock) throws InterruptedException {
        List<Quote> out = new ArrayList<>();
        for (int b = 0; b < cs.size(); b += 25) {
            List<Integer> batch = new ArrayList<>();
            for (Contract c : cs.subList(b, Math.min(b + 25, cs.size()))) {
                int id = ids.incrementAndGet();
                w.open(id);
                w.quotes.put(id, new Quote(stock));
                pace();
                client.reqMktData(id, c, "", true, false, null);
                batch.add(id);
            }
            // Full batches finish in 0.3-0.7 s (measured); a quote still missing after 1.5 s
            // is one IB never sends. Delayed data is slower to arrive.
            long deadline = System.currentTimeMillis() + (stock ? 6_000 : mktDataType >= 3 ? 4_000 : 1_500);
            for (int id : batch) w.awaitMs(id, Math.max(1, deadline - System.currentTimeMillis()));
            for (int id : batch) {
                pace();
                client.cancelMktData(id);          // frees the line now; late ticks are ignored below
                out.add(w.quotes.remove(id));
            }
        }
        return out;
    }

    /** At most one request every 25 ms: IB's API limit is 50 messages a second. */
    private static long lastMsg;
    static synchronized void pace() throws InterruptedException {
        long wait = lastMsg + 25 - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastMsg = System.currentTimeMillis();
    }

    static class Quote {
        final boolean stock;
        volatile double bid = Double.NaN, ask = Double.NaN, last = Double.NaN, close = Double.NaN;
        volatile boolean gotBid, gotAsk;   // IB sends -1 when a side has no quote
        volatile boolean delayed;          // prices arrived on IB's delayed fields
        volatile int dataType = 1;         // the feed IB says it is sending: 1 live, 2 frozen, 3/4 delayed
        volatile boolean saidLive;         // IB announced its live feed for this request
        volatile int err;                  // IB's error code for this request, 0 if none
        volatile String errMsg;

        Quote(boolean stock) { this.stock = stock; }

        /**
         * Options need only both sides; a stock with an empty side waits for a last or close.
         * Asked for frozen or delayed data, IB often sends an empty live quote first (-1 x -1),
         * then announces the frozen or delayed feed and sends the real prices: an empty quote
         * only counts once that switch has happened.
         */
        boolean complete() {
            if (bid > 0 && ask > 0) return true;
            if (!gotBid || !gotAsk) return false;
            if (stock) return last > 0 || close > 0;
            return mktDataType == 1 || dataType >= 2;
        }

        double price() {
            if (bid > 0 && ask > 0) return (bid + ask) / 2;
            if (last > 0) return last;
            return close;
        }
    }

    static class W extends DefaultEWrapper {
        record SecDef(String exchange, String tradingClass, String multiplier, Set<String> expirations,
                      Set<Double> strikes) {}

        volatile CountDownLatch connected = new CountDownLatch(1);
        volatile String connFail;          // why TWS refused the connection (e.g. client id in use)
        volatile boolean lost;             // the connection dropped during the scan
        final Map<Integer, CountDownLatch> done = new ConcurrentHashMap<>();
        final Map<Integer, List<ContractDetails>> details = new ConcurrentHashMap<>();
        final Map<Integer, List<Bar>> bars = new ConcurrentHashMap<>();
        final Map<Integer, List<SecDef>> secdefs = new ConcurrentHashMap<>();
        final Map<Integer, Quote> quotes = new ConcurrentHashMap<>();
        final Map<Integer, String> dividends = new ConcurrentHashMap<>();
        final Map<Integer, Integer> errCodes = new ConcurrentHashMap<>();
        final Map<Integer, String> errMsgs = new ConcurrentHashMap<>();

        void reset() { connected = new CountDownLatch(1); connFail = null; lost = false; }
        void open(int id) { done.put(id, new CountDownLatch(1)); }
        void finish(int id) { CountDownLatch l = done.get(id); if (l != null) l.countDown(); }
        void await(int id, long secs) throws InterruptedException { done.get(id).await(secs, TimeUnit.SECONDS); }
        void awaitMs(int id, long ms) throws InterruptedException { done.get(id).await(ms, TimeUnit.MILLISECONDS); }
        int errCode(int id) { return errCodes.getOrDefault(id, 0); }
        String errMsg(int id) { return errMsgs.get(id); }

        /** Connection gone: release every wait so the scan loop notices at once. */
        void dropped() {
            lost = true;
            for (CountDownLatch l : done.values()) l.countDown();
            connected.countDown();
        }

        @Override public void nextValidId(int id) { connected.countDown(); }
        @Override public void connectionClosed() { dropped(); }

        @Override public void contractDetails(int id, ContractDetails cd) {
            details.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(cd);
        }
        @Override public void contractDetailsEnd(int id) { finish(id); }

        @Override public void securityDefinitionOptionalParameter(int id, String exchange, int underlyingConId,
                String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
            secdefs.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>())
                    .add(new SecDef(exchange, tradingClass, multiplier, expirations, strikes));
        }
        @Override public void securityDefinitionOptionalParameterEnd(int id) { finish(id); }

        @Override public void historicalData(int id, Bar bar) {
            bars.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(bar);
        }
        @Override public void historicalDataEnd(int id, String start, String end) { finish(id); }

        @Override public void tickPrice(int id, int field, double price, TickAttrib attrib) {
            Quote q = quotes.get(id);
            if (q == null) return;
            switch (field) {
                case 1, 66 -> { q.gotBid = true; if (price > 0) q.bid = price; }
                case 2, 67 -> { q.gotAsk = true; if (price > 0) q.ask = price; }
                case 4, 68 -> { if (price > 0) q.last = price; }
                case 9, 75 -> { if (price > 0) q.close = price; }
                default -> { }
            }
            if (field >= 66 && field <= 76 && price > 0) q.delayed = true;
            if (q.complete()) finish(id);
        }

        @Override public void tickSnapshotEnd(int id) { finish(id); }

        @Override public void marketDataType(int id, int type) {
            Quote q = quotes.get(id);
            if (q == null) return;
            q.dataType = type;
            if (type == 1) q.saidLive = true;
            if (type >= 2) { q.gotBid = false; q.gotAsk = false; }   // the live placeholders don't count
            if (q.complete()) finish(id);
        }

        @Override public void tickString(int id, int tickType, String value) {
            if (tickType == 59) { dividends.put(id, value); finish(id); }   // IB_DIVIDENDS
        }

        @Override public void error(int id, long time, int code, String msg, String advancedOrderRejectJson) {
            switch (code) {
                case 2103, 2105, 2157 -> {            // a data farm is down
                    warn("farm" + code, "IB " + code + ": " + msg);
                    return;
                }
                case 326 -> {                         // client id in use: TWS drops us
                    connFail = "client_id " + clientId + " is already in use by another program on this TWS: close"
                            + " the other copy or change client_id in " + CONFIG + ".";
                    connected.countDown();
                    return;
                }
                case 1100 -> {                        // TWS lost its link to IB's servers
                    warn("ib1100", "IB 1100: TWS lost its connection to IB's servers.");
                    dropped();
                    return;
                }
                case 504 -> { dropped(); return; }     // not connected
                case 502 -> { dropped(); return; }     // IB reports TWS closing the socket as "couldn't connect"
                case 10167, 10090, 10091 -> { return; } // delayed or partial data: keep waiting
                case 300 -> { return; }                // cancel of a snapshot IB had already ended
                default -> { }
            }
            if (code >= 2100 && code < 2200) return;  // farm status chatter
            if (code == 1101 || code == 1102) return; // connectivity restored
            if (id > 0) {
                errCodes.putIfAbsent(id, code);
                if (msg != null) errMsgs.putIfAbsent(id, msg);
                Quote q = quotes.get(id);
                if (q != null && q.err == 0) { q.err = code; q.errMsg = msg; }
                if (code != 200) System.out.println("  IB " + code + " (req " + id + "): " + msg);
                finish(id);
            } else {
                warn("ib" + code, "IB " + code + ": " + msg);
            }
        }
        @Override public void error(Exception e) { System.out.println("IB exception: " + e); }
        @Override public void error(String s) { System.out.println("IB: " + s); }
    }

    // ------------------------------------------------------------------ output

    /** atFill: the same puts priced at the mid-fill price, keyed "SYM|strike". */
    static void print(List<Row> rows, Map<String, Row> atFill, List<String> skipped, ZonedDateTime now) {
        System.out.println();
        System.out.println("PUT SCREEN v" + VERSION + "  " + now.format(DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm", Locale.US)) + " ET"
                + "   edge (at the mid) and tail per contract ($); edge% = edge / cash secured;"
                + " score = edge/tail; prem% = mid / strike");
        System.out.printf("%-6s %-8s %8s %8s %6s %13s %6s %5s %8s %7s %8s %7s %6s %6s %5s %5s  %-8s %s%n",
                "SYM", "EXPIRY", "SPOT", "STRIKE", "DELTA", "BID x ASK", "IV", "IV/RV",
                "EDGE", "EDGE%", "TAIL", "SCORE", "SPRD%", "PREM%", "DIV$", "CTRS", "VERDICT", "AT FILL");
        for (Row r : rows) {
            Row f = atFill.get(r.sym + "|" + r.strike);
            System.out.printf("%-6s %-8s %8.2f %8.2f %6.2f %6.2f x %-6.2f %5.0f%% %5.2f %8.0f %7.3f %8.0f %7.3f %6.1f %6.2f %5.0f %5d  %-8s %s%n",
                    r.sym + (r.dividend > 0 ? "*" : ""), r.expiry, r.spot, r.strike, r.delta, r.bid, r.ask, r.iv * 100, r.ivRv,
                    r.edge * 100, r.edge / r.strike * 100, r.tail * 100, r.score, r.spreadPct, r.premPct,
                    r.divPart * 100, contracts(capital, r.strike), r.verdict, f != null ? f.verdict : "-");
        }
        System.out.printf("CTRS = cash-secured contracts for $%,.0f.  * = goes ex-dividend before expiry;"
                + " DIV$ = the dividend's share of the premium per contract.%n", capital);
        System.out.printf("AT FILL = the verdict if filled at bid + %.0f%% of the spread (fill_at %.2f), wide spreads allowed.%n",
                fillAt * 100, fillAt);
        for (Row r : rows) if (r.dividend > 0) System.out.println("  " + r.sym + " " + r.divNote);
        if (!skipped.isEmpty()) {
            System.out.println();
            System.out.println("SKIPPED");
            for (String s : skipped) System.out.println("  " + s);
        }
        System.out.println();
        System.out.println("Edge assumes a lognormal at recent realized volatility: it understates fat tails,"
                + " so read it as optimistic.");
    }

    /** Into a results folder next to the config. A scan cut short by a lost connection is named _PARTIAL. */
    static Path writeCsv(List<Row> rows, Map<String, Row> atFill, ZonedDateTime now, Path dir, boolean partial) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve("putscreen_" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + (partial ? "_PARTIAL" : "") + ".csv");
        try (PrintWriter p = new PrintWriter(Files.newBufferedWriter(f))) {
            p.println("sym,expiry,spot,strike,delta,bid,ask,iv,iv_rv,edge,edge_at_bid,tail,score,spread_pct,prem_pct,"
                    + "dividend,div_in_premium,ex_div,contracts,capital,verdict,"
                    + "fill_at,fill_price,fill_iv_rv,fill_edge,fill_verdict");
            for (Row r : rows) {
                Row x = atFill.get(r.sym + "|" + r.strike);
                p.printf(Locale.ROOT, "%s,%s,%.2f,%.2f,%.3f,%.2f,%.2f,%.4f,%.3f,%.2f,%.2f,%.2f,%.4f,%.2f,%.3f,%.4f,%.2f,%s,%d,%.0f,%s,"
                                + "%.2f,%s,%s,%s,%s%n",
                        r.sym, r.expiry, r.spot, r.strike, r.delta, r.bid, r.ask, r.iv, r.ivRv,
                        r.edge * 100, r.edgeAtBid * 100, r.tail * 100, r.score, r.spreadPct, r.premPct,
                        r.dividend, r.divPart * 100, r.divNote, contracts(capital, r.strike), capital, r.verdict,
                        fillAt,
                        x != null ? String.format(Locale.ROOT, "%.3f", x.price) : "",
                        x != null ? String.format(Locale.ROOT, "%.3f", x.ivRv) : "",
                        x != null ? String.format(Locale.ROOT, "%.2f", x.edge * 100) : "",
                        x != null ? x.verdict : "");
            }
        }
        System.out.println("Saved " + f.toAbsolutePath());
        return f;
    }

    /** A setting that must be a number; a bad one names itself instead of stopping with "For input string". */
    static double num(Properties p, String key, String dflt) {
        String v = p.getProperty(key, dflt).trim();
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(CONFIG + ": " + key + " = '" + v + "' is not a number (use a dot for decimals)");
        }
    }

    static void loadConfig(String path) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(Paths.get(path))) { p.load(r); }
        // Commas separate tickers; a space inside one is IB's class-share form (BRK B), which
        // BRK.B and BRK-B are turned into
        Set<String> uniq = new LinkedHashSet<>();
        for (String t : p.getProperty("tickers", "").split(",")) {
            String sym = t.trim().toUpperCase(Locale.ROOT).replace('.', ' ').replace('-', ' ').replaceAll("\\s+", " ");
            if (!sym.isEmpty()) uniq.add(sym);
        }
        tickers = new ArrayList<>(uniq);
        port = (int) num(p, "port", "7497");
        clientId = (int) num(p, "client_id", "4711");
        rvDays = (int) num(p, "rv_days", "20");
        if (rvDays < 2) throw new IllegalArgumentException(CONFIG + ": rv_days must be at least 2");
        deltaMin = num(p, "delta_min", "0.20");
        deltaMax = num(p, "delta_max", "0.40");
        maxSpreadPct = num(p, "max_spread_pct", "20");
        minIvRv = num(p, "min_iv_rv", "1.15");
        gapMultiple = num(p, "gap_multiple", "3");
        String cap = p.getProperty("capital", "25000").replace(",", "").replace("$", "").trim();
        try {
            capital = Double.parseDouble(cap);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(CONFIG + ": capital = '" + cap + "' is not a number");
        }
        includeDividends = !p.getProperty("dividends", "include").trim().equalsIgnoreCase("skip");
        fillAt = Math.max(0, Math.min(1, num(p, "fill_at", "0.5")));
        midFill = Boolean.parseBoolean(p.getProperty("mid_fill", "false").trim());
        delayedData = Boolean.parseBoolean(p.getProperty("delayed_data", "false").trim());
        nasdaqEarnings = p.getProperty("earnings_calendar", "off").trim().equalsIgnoreCase("nasdaq");
    }
}
