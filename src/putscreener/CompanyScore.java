package putscreener;

import com.ib.client.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Company scores, 0-100, for the tickers in putscreener.properties. Run it once a week before
 * screening: it writes company_scores.csv next to the settings file. Delete the rows you don't want
 * to screen that week, and PutScreener then scans only the names left in it and shows their scores.
 *
 * The figures come from the companies' own 10-K and 10-Q filings, through the SEC's free XBRL API
 * (data.sec.gov, no key; the SEC asks for a contact, sec_contact in the settings). Prices come from
 * IB, so TWS must be running. Six measures, each scored 0-100 along a line through fixed points,
 * then weighted:
 *
 *   P/E             price / trailing-twelve-month diluted EPS            10
 *   FCF yield       TTM (operating cash flow - capex) / market cap       20
 *   Net debt/EBITDA (debt - cash - short-term investments) / TTM EBITDA  15
 *   FCF trend       yearly growth of FCF over three years, to the TTM    20
 *   ROIC            TTM operating income after tax / (equity + debt)     20
 *   Market cap      price x diluted shares                                5
 *
 * A measure the filings don't give is left out and the others are reweighted. Banks and insurers
 * get P/E and size only: debt, EBITDA and cash flow mean something else for them.
 *
 * Read-only: nothing here places an order.
 */
public class CompanyScore {

    static final String SCORES = "company_scores.csv";

    public static void main(String[] args) {
        try {
            run(Arrays.stream(args).filter(a -> !a.startsWith("--")).findFirst().orElse(null));
        } catch (PutScreener.ScanStop e) {
            System.out.println("STOPPED  " + e.getMessage());
            System.exit(1);
        } catch (FileNotFoundException | IllegalArgumentException e) {   // a settings problem: the message says it
            System.out.println("FAILED  " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("FAILED  " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            System.exit(1);
        }
        System.exit(0);
    }

    static void run(String given) throws Exception {
        Path cfg = PutScreener.findConfig(given);
        if (PutScreener.createdConfig != null) {
            System.out.println("No settings file was found, so a starter one was written: " + cfg);
            System.out.println("Set tickers, port and sec_contact there, then run this again.");
            return;
        }
        PutScreener.loadConfig(cfg.toString());
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(cfg)) { p.load(r); }
        String contact = p.getProperty("sec_contact", "").trim();
        if (!contact.contains("@"))
            throw new PutScreener.ScanStop("Set sec_contact in " + cfg + " to your name and email, e.g."
                    + " \"sec_contact = Jane Doe jane@example.com\". The SEC asks every program that downloads its"
                    + " data for a contact; it is sent only to the SEC.");
        List<String> syms = PutScreener.tickers;
        if (syms.isEmpty()) throw new PutScreener.ScanStop("No tickers in " + cfg);
        System.out.println("Config: " + cfg);
        System.out.println("Scoring " + syms.size() + " companies.");

        // Prices first: if TWS isn't there, say so before a minute of downloading
        Map<String, Double> prices = prices(syms);

        Sec sec = new Sec(contact, cfg.getParent().resolve("sec_cache"));
        Map<String, Integer> ciks = sec.tickerMap();
        LocalDate today = LocalDate.now(PutScreener.NY);
        List<Company> out = new ArrayList<>();
        for (int i = 0; i < syms.size(); i++) {
            String sym = syms.get(i);
            System.out.printf("%3d/%d  %-6s ", i + 1, syms.size(), display(sym));
            Company c = new Company(sym);
            Integer cik = ciks.get(sym.replace(' ', '-'));
            if (cik == null) {
                c.notes.add("not in the SEC's ticker list");
                System.out.println("not in the SEC's ticker list");
                out.add(c);
                continue;
            }
            try {
                Facts f = new Facts();
                f.add(sec.facts(cik));
                Integer old = PREDECESSOR.get(sym);
                if (old != null) f.add(sec.facts(old));
                Map<String, Object> sub = sec.submissions(cik);
                c.cik = cik;
                c.name = clean(str(sub.get("name"), str(f.name, sym)));
                c.sic = str(sub.get("sic"), "");
                c.industry = clean(str(sub.get("sicDescription"), ""));
                extract(c, f, today);
                c.price = prices.get(sym);
                if (c.price == null) c.notes.add("no price from IB");
                score(c);
                System.out.println((c.score == null ? "  -" : String.format(Locale.ROOT, "%3.0f", c.score)) + "  " + c.name
                        + (c.notes.isEmpty() ? "" : "   [" + String.join("; ", c.notes) + "]"));
            } catch (IOException | RuntimeException e) {     // one company's bad data must not stop the rest
                String why = e instanceof IOException ? "SEC download failed: " + e.getMessage() : "unreadable SEC data (" + e + ")";
                c.notes.add(why);
                System.out.println(why);
            }
            out.add(c);
        }

        out.sort(Comparator.comparing((Company c) -> c.score == null ? -1 : c.score).reversed());
        ZonedDateTime now = ZonedDateTime.now(PutScreener.NY);
        Path dir = cfg.getParent();
        Path copy = dir.resolve("results").resolve("company_scores_" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".csv");
        Files.createDirectories(copy.getParent());
        write(out, copy, now);
        Path working = dir.resolve(SCORES);
        try {
            write(out, working, now);
        } catch (IOException e) {
            throw new PutScreener.ScanStop(working + " can't be written (" + e.getClass().getSimpleName()
                    + "): is it open in Excel? Close it and run this again. This run's scores are in " + copy);
        }
        long scored = out.stream().filter(c -> c.score != null).count();
        System.out.println();
        System.out.println("Scored " + scored + " of " + out.size() + ". Saved " + working);
        System.out.println("A copy is in " + copy);
        System.out.println("Delete the rows you don't want to screen this week; PutScreener then scans only the names left.");
    }

    // ------------------------------------------------------------------ prices from IB

    /**
     * Last price per symbol. Frozen market data: live during the session, the last price when the
     * market is shut (delayed-frozen with delayed_data = true).
     */
    static Map<String, Double> prices(List<String> syms) throws Exception {
        PutScreener.mktDataType = PutScreener.delayedData ? 4 : 2;
        System.out.println("Prices from IB on port " + PutScreener.port + "...");
        PutScreener.connect();
        Map<String, Double> out = new HashMap<>();
        try {
            List<Contract> cs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String sym : syms) {
                if (PutScreener.w.lost || !PutScreener.client.isConnected()) break;
                Contract stk = new Contract();
                stk.symbol(sym); stk.secType("STK"); stk.exchange("SMART"); stk.currency("USD");
                List<ContractDetails> d = PutScreener.contractDetails(stk);
                if (d.isEmpty()) { System.out.println("  IB doesn't recognise " + display(sym)); continue; }
                Contract s = d.get(0).contract();
                s.exchange("SMART");
                cs.add(s);
                names.add(sym);
            }
            List<PutScreener.Quote> qs = PutScreener.snapshots(cs, true);
            PutScreener.Quote refused = null;
            for (int i = 0; i < qs.size(); i++) {
                double px = qs.get(i).price();
                if (px > 0) out.put(names.get(i), px);
                else if (refused == null && qs.get(i).err != 0) refused = qs.get(i);
            }
            // Without prices a company has no P/E or FCF yield and gets no total: stop rather than
            // write a list with holes in it
            if (PutScreener.w.lost || !PutScreener.client.isConnected())
                throw new PutScreener.ScanStop("Lost the connection to TWS while getting prices. Run this again when TWS is back.");
            if (out.isEmpty())
                throw new PutScreener.ScanStop("IB sent no prices" + (refused != null ? " (IB " + refused.err + ": " + refused.errMsg + ")" : "")
                        + ". Check TWS and the account's US stock data, or set delayed_data = true.");
            System.out.println("  " + out.size() + " of " + syms.size() + " prices.");
        } finally {
            PutScreener.disconnect();
        }
        return out;
    }

    // ------------------------------------------------------------------ one company

    static final class Company {
        final String sym;
        String name = "", sic = "", industry = "";
        Integer cik;
        boolean financial;
        Double price;
        // From the filings; money in dollars, null = not found
        Double epsTtm, netIncome, cfo, capex, fcf, opIncome, da, ebitda, debt, cash, sti, equity, shares, taxRate;
        LocalDate dataThrough, balanceDate;
        final TreeMap<LocalDate, Double> fcfByYear = new TreeMap<>();
        Double fcfCagr;          // % a year, over about three years to the TTM
        boolean fcfTurnedPositive, fcfNegative;
        // Derived with the price
        Double pe, fcfYield, ndEbitda, roic, mktCapB;
        Double sPe, sFcfYield, sNdEbitda, sFcfTrend, sRoic, sMktCap, score;
        int metrics;
        final List<String> notes = new ArrayList<>();

        Company(String sym) { this.sym = sym; }
    }

    /** A company that moved under a new holding company keeps its history under its old CIK. */
    static final Map<String, Integer> PREDECESSOR = Map.of("XOM", 34088);   // ExxonMobil Holdings, 2026

    // Tag chains: the first tag with a figure for the latest period wins, and one tag is used for
    // the whole of each figure (never one tag for the year and another for the quarters)
    static final List<String> CFO = List.of("NetCashProvidedByUsedInOperatingActivities",
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations");
    static final List<String> CAPEX = List.of("PaymentsToAcquirePropertyPlantAndEquipment", "PaymentsToAcquireProductiveAssets",
            "PaymentsForCapitalImprovements", "PaymentsToAcquireOilAndGasPropertyAndEquipment", "PaymentsToAcquireOilAndGasProperty");
    static final List<String> NET_INCOME = List.of("NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic");
    static final List<String> EPS = List.of("EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted", "EarningsPerShareBasic");
    static final List<String> OP_INCOME = List.of("OperatingIncomeLoss");
    static final List<String> PRETAX = List.of("IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments");
    static final List<String> INTEREST = List.of("InterestExpense", "InterestExpenseNonoperating", "InterestExpenseDebt",
            "InterestAndDebtExpense");
    static final List<String> DA = List.of("DepreciationDepletionAndAmortization", "DepreciationAmortizationAndAccretionNet",
            "DepreciationAndAmortization");
    static final List<String> TAX = List.of("IncomeTaxExpenseBenefit");
    static final List<String> DILUTED = List.of("WeightedAverageNumberOfDilutedSharesOutstanding");
    static final List<String> BASIC = List.of("WeightedAverageNumberOfSharesOutstandingBasic");
    static final List<String> CASH = List.of("CashAndCashEquivalentsAtCarryingValue",
            "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents", "Cash");
    static final List<String> STI = List.of("ShortTermInvestments", "OtherShortTermInvestments", "MarketableSecuritiesCurrent",
            "AvailableForSaleSecuritiesDebtSecuritiesCurrent", "HeldToMaturitySecuritiesCurrent");
    static final List<String> EQUITY = List.of("StockholdersEquity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest");
    static final List<String> DEBT_ALL = List.of("DebtLongtermAndShorttermCombinedAmount");
    static final List<String> DEBT_LONG = List.of("LongTermDebt", "LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities");
    static final List<String> DEBT_NONCURRENT = List.of("LongTermDebtNoncurrent", "LongTermDebtAndCapitalLeaseObligations");
    static final List<String> DEBT_CURRENT = List.of("LongTermDebtCurrent", "LongTermDebtAndCapitalLeaseObligationsCurrent");
    static final List<String> DEBT_NOTES = List.of("NotesPayable", "SeniorNotes", "LongTermNotesPayable");
    static final List<String> DEBT_SHORT = List.of("ShortTermBorrowings", "CommercialPaper", "OtherShortTermBorrowings");

    /**
     * Everything the score needs from the filings. Every figure must reach the company's latest
     * reported period: a tag the company stopped using years ago still sits in the data (J&J's
     * operating income ends in 2015), and would otherwise pass for this year's.
     */
    static void extract(Company c, Facts f, LocalDate today) {
        int sic = c.sic.matches("\\d+") ? Integer.parseInt(c.sic) : 0;
        // Banks, card issuers, brokers and insurers; health insurers (6324) report like other companies
        c.financial = (sic >= 6000 && sic <= 6199) || sic == 6211 || (sic >= 6300 && sic <= 6399 && sic != 6324);

        LocalDate last = f.latestPeriodEnd(CFO, NET_INCOME);
        if (last == null) { c.notes.add("no income or cash-flow figures in the SEC data"); return; }
        c.dataThrough = last;
        if (ChronoUnit.DAYS.between(last, today) > 160)
            c.notes.add("SEC data ends " + last + ": a newer report may not be in its feed yet");

        Ttm cfo = f.current(CFO, "USD", last);
        Ttm capex = f.current(CAPEX, "USD", last);
        if (cfo != null) c.cfo = cfo.value;
        if (capex != null) c.capex = capex.value;
        if (cfo != null && capex != null) c.fcf = cfo.value - capex.value;
        else if (!c.financial) c.notes.add((cfo == null ? "no operating cash flow" : "no capex")
                + " for the latest period in the SEC's standard tags: no free cash flow");

        Ttm ni = f.current(NET_INCOME, "USD", last);
        if (ni != null) c.netIncome = ni.value;
        Ttm eps = f.current(EPS, "USD/shares", last);
        if (eps != null) c.epsTtm = eps.value;

        // Shares: the latest quarter's diluted average; else net income / EPS (consistent with the
        // EPS by construction); else the basic average. The cover page's count is not used: it
        // double-counts some share classes and misses others.
        Double dil = f.quarterAt(DILUTED, "shares", last);
        if (dil != null) c.shares = dil;
        else if (c.netIncome != null && c.epsTtm != null && Math.abs(c.epsTtm) >= 0.05 && c.netIncome * c.epsTtm > 0)
            c.shares = c.netIncome / c.epsTtm;
        else {
            Double basic = f.quarterAt(BASIC, "shares", last);
            if (basic != null) c.shares = basic;
        }
        if (c.epsTtm == null && c.netIncome != null && c.shares != null) {
            c.epsTtm = c.netIncome / c.shares;
            c.notes.add("EPS = net income / diluted shares");
        }
        if (c.epsTtm == null || c.shares == null) c.notes.add("no company-wide EPS or share count (share classes)");

        Ttm op = f.current(OP_INCOME, "USD", last);
        if (op != null) c.opIncome = op.value;
        else {
            Ttm pre = f.current(PRETAX, "USD", last);
            if (pre != null) {
                Ttm in = f.current(INTEREST, "USD", last);
                c.opIncome = pre.value + (in != null ? in.value : 0);
                c.notes.add(in != null ? "EBIT = pretax income + interest" : "EBIT = pretax income");
            }
        }
        Ttm da = f.current(DA, "USD", last);
        if (da != null) c.da = da.value;
        else {
            Ttm dep = f.current(List.of("Depreciation"), "USD", last);
            if (dep != null) {
                Ttm am = f.current(List.of("AmortizationOfIntangibleAssets"), "USD", last);
                c.da = dep.value + (am != null ? am.value : 0);
            }
        }
        if (c.opIncome != null && c.da != null) c.ebitda = c.opIncome + c.da;

        Ttm tax = f.current(TAX, "USD", last);
        Ttm pre = f.current(PRETAX, "USD", last);
        c.taxRate = tax != null && pre != null && pre.value > 0 ? Math.min(Math.max(tax.value / pre.value, 0), 0.35) : 0.21;

        // Balance sheet at the latest period's end
        Point cash = f.instantNear(CASH, last, 7);
        LocalDate bs = cash != null ? cash.date : last;
        c.balanceDate = bs;
        if (cash != null) c.cash = cash.value;
        Point sti = f.instantNear(STI, bs, 3);
        c.sti = sti != null ? sti.value : 0.0;
        Point eq = f.instantNear(EQUITY, bs, 3);
        if (eq != null) c.equity = eq.value;
        c.debt = debt(c, f, bs);

        // Free cash flow by fiscal year, and its trend to the TTM
        if (cfo != null) {
            TreeSet<LocalDate> ends = new TreeSet<>();
            for (String tag : CFO) ends.addAll(f.annualEnds(tag));
            for (LocalDate end : ends.descendingSet()) {
                if (c.fcfByYear.size() == 5) break;
                Double a = f.annual(CFO, "USD", end), b = f.annual(CAPEX, "USD", end);
                if (a != null && b != null) c.fcfByYear.put(end, a - b);
            }
        }
        if (c.fcf != null) {
            // From the fiscal year ending nearest three years back (up to half a year either
            // way: the latest period is usually a quarter, not a year end) to the TTM
            LocalDate target = last.minusYears(3), start = null;
            for (LocalDate end : c.fcfByYear.keySet()) {
                long d = Math.abs(ChronoUnit.DAYS.between(end, target));
                if (d <= 200 && (start == null || d < Math.abs(ChronoUnit.DAYS.between(start, target)))) start = end;
            }
            if (start == null) c.notes.add("no fiscal year about three years back: no FCF trend");
            else {
                double first = c.fcfByYear.get(start), years = ChronoUnit.DAYS.between(start, last) / 365.25;
                if (first > 0 && c.fcf > 0) c.fcfCagr = (Math.pow(c.fcf / first, 1 / years) - 1) * 100;
                else if (c.fcf > 0) c.fcfTurnedPositive = true;
                else c.fcfNegative = true;          // no growth rate: the bottom of the scale
            }
        }
    }

    /**
     * Long-term debt with its current part, plus short-term borrowings, at the balance-sheet date.
     * A company that tags debt only at year end gets that figure (noted). One that has never used
     * a debt tag is taken to have none (T. Rowe Price); one that used to but no longer does keeps
     * its debt under its own tags, which the SEC's feed leaves out (PACCAR): unknown, not zero.
     */
    static Double debt(Company c, Facts f, LocalDate bs) {
        Double d = debtAt(f, bs, 3);
        if (d != null) return d;
        for (List<String> chain : List.of(DEBT_ALL, DEBT_LONG, DEBT_NONCURRENT, DEBT_NOTES)) {
            Point old = f.latestInstant(chain, bs);
            if (old != null && ChronoUnit.DAYS.between(old.date, bs) <= 200) {
                d = debtAt(f, old.date, 0);
                if (d != null) { c.notes.add("debt as of " + old.date); return d; }
            }
        }
        for (List<String> chain : List.of(DEBT_ALL, DEBT_LONG, DEBT_NONCURRENT, DEBT_CURRENT, DEBT_NOTES, DEBT_SHORT, List.of("DebtCurrent")))
            if (f.latestInstant(chain, bs) != null) {
                c.notes.add("debt not in the SEC's standard tags");
                return null;
            }
        if (c.financial) return null;        // a bank or insurer's borrowing sits under its own tags
        c.notes.add("no debt reported");
        return 0.0;
    }

    /**
     * Total debt on one balance-sheet date: the long-term line plus everything due within a year.
     * Companies tag the same lines differently, so, in order:
     *  - DebtCurrent is all debt due within a year (commercial paper, other short-term borrowings,
     *    current maturities): long-term non-current + DebtCurrent (Cisco, Exxon, AT&T, Intel). A
     *    company with no non-current tag files its long-term line as LongTermDebt (Qualcomm, Adobe).
     *  - Otherwise the current parts are added: current maturities + short-term borrowings, where
     *    a short-term line that already holds the current maturities counts once (Yum, Applied
     *    Materials, IBM, whose short-term line is about the same as its current maturities, or
     *    PepsiCo's, which is more than current maturities and commercial paper together);
     *    commercial paper is usually inside short-term borrowings, so the larger of the two counts.
     *  - A long-term figure with its current part and no split: that plus short-term borrowings.
     */
    static Double debtAt(Facts f, LocalDate date, int tol) {
        Double nonCurrent = val(f.instantNear(DEBT_NONCURRENT, date, tol));
        Double longTerm = val(f.instantNear(DEBT_LONG, date, tol));
        Double currentAll = val(f.instantNear(List.of("DebtCurrent"), date, tol));
        Double currentMat = val(f.instantNear(DEBT_CURRENT, date, tol));
        Double stb = val(f.instantNear(List.of("ShortTermBorrowings", "OtherShortTermBorrowings"), date, tol));
        Double cp = val(f.instantNear(List.of("CommercialPaper"), date, tol));
        if (currentAll != null) {
            Double lt = nonCurrent != null ? nonCurrent : longTerm;
            if (lt != null) return lt + currentAll;
        }
        if (nonCurrent != null || (longTerm != null && currentMat != null)) {
            double lt = nonCurrent != null ? nonCurrent : longTerm - currentMat;
            double mat = currentMat != null ? currentMat : 0;
            double shortTerm = Math.max(stb != null ? stb : 0, cp != null ? cp : 0);
            double current;
            if (stb != null && mat > 0 && stb >= mat * 0.98 && stb - mat <= 0.1 * stb) current = stb;
            else if (stb != null && cp != null && mat > 0 && stb >= (mat + cp) * 0.98) current = stb;
            else current = mat + shortTerm;
            return lt + current;
        }
        if (longTerm != null) return longTerm + Math.max(stb != null ? stb : 0, cp != null ? cp : 0);
        Double all = val(f.instantNear(DEBT_ALL, date, tol));
        if (all != null) return all;
        Double notes = val(f.instantNear(DEBT_NOTES, date, tol));
        return notes;
    }

    static Double val(Point p) { return p == null ? null : p.value; }

    // ------------------------------------------------------------------ scoring

    // Each measure scores along straight lines through these points, flat beyond the ends
    static final double[][] PE_PTS = {{8, 100}, {12, 90}, {16, 75}, {20, 60}, {25, 40}, {35, 15}, {50, 0}};
    static final double[][] FCF_YIELD_PTS = {{0, 0}, {2, 20}, {4, 45}, {6, 65}, {8, 80}, {10, 90}, {15, 100}};
    static final double[][] ND_EBITDA_PTS = {{0, 100}, {0.5, 95}, {1, 85}, {1.5, 75}, {2, 60}, {3, 35}, {4, 15}, {5, 0}};
    static final double[][] FCF_CAGR_PTS = {{-15, 0}, {-10, 5}, {-5, 20}, {0, 45}, {5, 65}, {10, 80}, {15, 90}, {20, 100}};
    static final double[][] ROIC_PTS = {{0, 0}, {5, 25}, {8, 45}, {10, 60}, {12, 70}, {15, 80}, {20, 95}, {25, 100}};
    static final double[][] MKT_CAP_PTS = {{0, 0}, {2, 15}, {5, 30}, {10, 50}, {25, 65}, {50, 75}, {100, 85}, {500, 100}};
    static final double W_PE = 10, W_FCF_YIELD = 20, W_ND_EBITDA = 15, W_FCF_TREND = 20, W_ROIC = 20, W_MKT_CAP = 5;
    /** A turn from negative to positive free cash flow has no growth rate: it scores as about 10% a year. */
    static final double TURNED_POSITIVE = 80;

    static double line(double[][] pts, double x) {
        if (x <= pts[0][0]) return pts[0][1];
        for (int i = 1; i < pts.length; i++) {
            if (x <= pts[i][0]) {
                double f = (x - pts[i - 1][0]) / (pts[i][0] - pts[i - 1][0]);
                return pts[i - 1][1] + f * (pts[i][1] - pts[i - 1][1]);
            }
        }
        return pts[pts.length - 1][1];
    }

    static void score(Company c) {
        if (c.price != null && c.epsTtm != null) {
            c.pe = c.epsTtm > 0 ? c.price / c.epsTtm : null;
            c.sPe = c.epsTtm > 0 ? line(PE_PTS, c.pe) : 0;      // a loss scores zero
        }
        if (c.price != null && c.shares != null) {
            c.mktCapB = c.price * c.shares / 1e9;
            c.sMktCap = line(MKT_CAP_PTS, c.mktCapB);
        }
        if (!c.financial) {
            if (c.fcf != null && c.mktCapB != null) {
                c.fcfYield = c.fcf / (c.mktCapB * 1e9) * 100;
                c.sFcfYield = line(FCF_YIELD_PTS, c.fcfYield);
            }
            if (c.debt != null && c.cash != null) {
                double net = c.debt - c.cash - c.sti;
                if (net <= 0) { c.ndEbitda = c.ebitda != null && c.ebitda > 0 ? net / c.ebitda : null; c.sNdEbitda = 100.0; }
                else if (c.ebitda != null && c.ebitda > 0) { c.ndEbitda = net / c.ebitda; c.sNdEbitda = line(ND_EBITDA_PTS, c.ndEbitda); }
                else if (c.ebitda != null) c.sNdEbitda = 0.0;   // debt and no earnings to carry it
            }
            if (c.fcfCagr != null) c.sFcfTrend = line(FCF_CAGR_PTS, c.fcfCagr);
            else if (c.fcfTurnedPositive) { c.sFcfTrend = TURNED_POSITIVE; c.notes.add("free cash flow turned positive"); }
            else if (c.fcfNegative) { c.sFcfTrend = 0.0; c.notes.add("negative free cash flow"); }
            if (c.opIncome != null && c.equity != null && c.debt != null && c.equity + c.debt > 0) {
                c.roic = c.opIncome * (1 - c.taxRate) / (c.equity + c.debt) * 100;
                c.sRoic = line(ROIC_PTS, c.roic);
            }
        } else {
            // P/E and size alone would put every bank near the top: shown, but no total
            c.notes.add("bank/insurer: not scored, debt and cash-flow measures don't fit");
        }
        double sum = 0, weight = 0;
        double[][] parts = {{nz(c.sPe), W_PE, c.sPe == null ? 0 : 1}, {nz(c.sFcfYield), W_FCF_YIELD, c.sFcfYield == null ? 0 : 1},
                {nz(c.sNdEbitda), W_ND_EBITDA, c.sNdEbitda == null ? 0 : 1}, {nz(c.sFcfTrend), W_FCF_TREND, c.sFcfTrend == null ? 0 : 1},
                {nz(c.sRoic), W_ROIC, c.sRoic == null ? 0 : 1}, {nz(c.sMktCap), W_MKT_CAP, c.sMktCap == null ? 0 : 1}};
        for (double[] p : parts) {
            if (p[2] == 0) continue;
            sum += p[0] * p[1];
            weight += p[1];
            c.metrics++;
        }
        // A total needs a valuation measure: without a share count (Visa's is only per share class)
        // or a price, leverage and returns alone would put a company at the top of the list
        boolean valued = c.sPe != null || c.sFcfYield != null;
        if (!c.financial && !valued) c.notes.add("no P/E or FCF yield: no total");
        c.score = c.metrics >= 2 && !c.financial && valued ? (double) Math.round(sum / weight) : null;
    }

    static double nz(Double d) { return d == null ? 0 : d; }

    // ------------------------------------------------------------------ output

    static final String HEADER = "company,symbol,score,pe_score,fcf_yield_score,net_debt_ebitda_score,fcf_trend_score,"
            + "roic_score,market_cap_score,measures,price,pe,fcf_yield_pct,net_debt_ebitda,fcf_cagr_3y_pct,roic_pct,market_cap_b,"
            + "eps_ttm,net_income_m,cfo_m,capex_m,fcf_m,fcf_by_fiscal_year_m,operating_income_m,da_m,ebitda_m,debt_m,cash_m,"
            + "short_term_inv_m,equity_m,shares_m,tax_rate_pct,data_through,balance_sheet_date,financial,sic,industry,cik,"
            + "notes,scored_on";

    /** Money in millions, so Excel shows it without switching to 1.2E+11. */
    static void write(List<Company> cs, Path file, ZonedDateTime now) throws IOException {
        String on = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);   // 20260928: no offset, and Excel keeps it
        try (PrintWriter p = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            p.println(HEADER);
            for (Company c : cs) {
                StringJoiner fy = new StringJoiner(" | ");
                for (Map.Entry<LocalDate, Double> e : c.fcfByYear.entrySet())
                    // a 52/53-week year ending January 1-7 belongs to the year before (J&J)
                    fy.add("FY" + e.getKey().minusDays(7).getYear() + " " + fmt(e.getValue() / 1e6, "%.0f"));
                List<String> v = List.of(
                        csv(c.name), display(c.sym), fmt(c.score, "%.0f"),
                        fmt(c.sPe, "%.0f"), fmt(c.sFcfYield, "%.0f"), fmt(c.sNdEbitda, "%.0f"), fmt(c.sFcfTrend, "%.0f"),
                        fmt(c.sRoic, "%.0f"), fmt(c.sMktCap, "%.0f"), c.metrics + " of 6",   // "5/6" would become a date in Excel
                        fmt(c.price, "%.2f"), fmt(c.pe, "%.1f"), fmt(c.fcfYield, "%.2f"), fmt(c.ndEbitda, "%.2f"),
                        fmt(c.fcfCagr, "%.1f"), fmt(c.roic, "%.1f"), fmt(c.mktCapB, "%.1f"),
                        fmt(c.epsTtm, "%.2f"), m(c.netIncome), m(c.cfo), m(c.capex), m(c.fcf), csv(fy.toString()),
                        m(c.opIncome), m(c.da), m(c.ebitda), m(c.debt), m(c.cash), m(c.sti), m(c.equity), m(c.shares),
                        c.taxRate == null ? "" : fmt(c.taxRate * 100, "%.1f"),
                        c.dataThrough == null ? "" : c.dataThrough.toString(), c.balanceDate == null ? "" : c.balanceDate.toString(),
                        c.financial ? "yes" : "", c.sic, csv(c.industry), c.cik == null ? "" : c.cik.toString(),
                        csv(String.join("; ", c.notes)), on);
                p.println(String.join(",", v));
            }
        }
    }

    static String m(Double d) { return d == null ? "" : fmt(d / 1e6, "%.0f"); }

    static String fmt(Double d, String f) { return d == null || d.isNaN() ? "" : String.format(Locale.ROOT, f, d); }

    static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") || s.contains(";") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** IB's class-share form "BRK B", written the way the settings file has it. */
    static String display(String sym) { return sym.replace(' ', '.'); }

    /** SEC names carry odd characters (a stray byte in Merck's): plain ASCII, single spaces. */
    static String clean(String s) { return s.replaceAll("[^\\x20-\\x7E]", " ").replaceAll("\\s+", " ").trim(); }

    static String str(Object o, String dflt) { return o == null || o.toString().isBlank() ? dflt : o.toString(); }

    // ------------------------------------------------------------------ SEC facts

    record Span(LocalDate start, LocalDate end) {
        long days() { return ChronoUnit.DAYS.between(start, end); }
    }

    record Ttm(double value, LocalDate end, String concept) {}

    record Point(double value, LocalDate date) {}

    /**
     * One company's us-gaap facts from companyfacts. Only the 10-K and 10-Q family counts: proxy
     * statements tag net income in their pay tables too, often in millions labelled as dollars, and
     * being filed later they would win (FedEx's FY2026 net income came out as $4,433).
     */
    static final class Facts {
        static final Set<String> FORMS = Set.of("10-K", "10-K/A", "10-Q", "10-Q/A", "10-KT", "10-QT",
                "20-F", "20-F/A", "40-F", "40-F/A");
        final Map<String, List<Map<String, Object>>> raw = new HashMap<>();   // "concept|unit" -> facts
        Object name;

        @SuppressWarnings("unchecked")
        void add(Map<String, Object> root) {
            if (name == null) name = root.get("entityName");
            Object facts = root.get("facts");
            if (!(facts instanceof Map)) return;
            Object gaap = ((Map<String, Object>) facts).get("us-gaap");
            if (!(gaap instanceof Map)) return;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) gaap).entrySet()) {
                Object units = ((Map<String, Object>) e.getValue()).get("units");
                if (!(units instanceof Map)) continue;
                for (Map.Entry<String, Object> u : ((Map<String, Object>) units).entrySet())
                    for (Object o : (List<Object>) u.getValue())
                        raw.computeIfAbsent(e.getKey() + "|" + u.getKey(), k -> new ArrayList<>()).add((Map<String, Object>) o);
            }
        }

        /** Span -> value, the latest-filed figure for each period. */
        Map<Span, Double> periods(String concept, String unit) {
            Map<Span, Double> out = new HashMap<>();
            Map<Span, String> filed = new HashMap<>();
            for (Map<String, Object> o : raw.getOrDefault(concept + "|" + unit, List.of())) {
                if (!FORMS.contains(String.valueOf(o.get("form"))) || !(o.get("val") instanceof Double v)) continue;
                Object s = o.get("start");
                Span k = new Span(s == null ? null : LocalDate.parse(s.toString()), LocalDate.parse(o.get("end").toString()));
                String fd = String.valueOf(o.get("filed"));
                if (!filed.containsKey(k) || fd.compareTo(filed.get(k)) > 0) { filed.put(k, fd); out.put(k, v); }
            }
            return out;
        }

        /** A period's figure as first filed (before any restatement). */
        Double firstFiled(String concept, String unit, Span k) {
            Double v = null;
            String filed = null;
            for (Map<String, Object> o : raw.getOrDefault(concept + "|" + unit, List.of())) {
                if (!FORMS.contains(String.valueOf(o.get("form"))) || !(o.get("val") instanceof Double x)) continue;
                Object s = o.get("start");
                if (s == null || !LocalDate.parse(s.toString()).equals(k.start()) || !LocalDate.parse(o.get("end").toString()).equals(k.end()))
                    continue;
                String fd = String.valueOf(o.get("filed"));
                if (filed == null || fd.compareTo(filed) < 0) { filed = fd; v = x; }
            }
            return v;
        }

        /** The last period end of any duration figure in these tag chains: the company's latest report. */
        @SafeVarargs
        final LocalDate latestPeriodEnd(List<String>... chains) {
            LocalDate last = null;
            for (List<String> chain : chains)
                for (String c : chain)
                    for (Span p : periods(c, "USD").keySet())
                        if (p.start() != null && (last == null || p.end().isAfter(last))) last = p.end();
            return last;
        }

        static boolean near(LocalDate a, LocalDate b) { return Math.abs(ChronoUnit.DAYS.between(a, b)) <= 7; }

        static boolean isYear(Span k) { return k.start() != null && k.days() >= 350 && k.days() <= 380; }

        /**
         * The trailing twelve months ending at the latest period, from the first tag in the chain
         * that reports that period; null if none does, so a tag the company stopped using years ago
         * never passes for this year's. At a fiscal year end it is the year's own figure, preferring
         * a tag the company also uses in its 10-Qs over a 10-K note's rounded one (Salesforce's D&A).
         * Otherwise it is last fiscal year + this year-to-date - last year's year-to-date. The two
         * year-to-dates come from one tag: a filing's figure and its own comparative. The fiscal year
         * comes from that tag when it has it, else from the chain's first tag that does: a company
         * that renames a tag files last year's comparatives under the new name, but its 10-K sits
         * under the old one (AT&T's operating cash flow, Cboe's capex).
         */
        Ttm current(List<String> chain, String unit, LocalDate last) {
            Ttm yearOnly = null;
            for (String c : chain) {
                Map<Span, Double> p = periods(c, unit);
                for (Map.Entry<Span, Double> e : p.entrySet()) {
                    Span k = e.getKey();
                    if (!isYear(k) || !near(k.end(), last)) continue;
                    Ttm t = new Ttm(e.getValue(), k.end(), c);
                    if (usedInQuarters(p, k.end())) return t;
                    if (yearOnly == null) yearOnly = t;
                }
            }
            if (yearOnly != null) return yearOnly;
            for (String c : chain) {
                Map<Span, Double> p = periods(c, unit);
                Span ytd = null;                       // the longest period ending now: the year-to-date
                for (Span k : p.keySet())
                    if (k.start() != null && k.days() >= 80 && k.days() <= 290 && near(k.end(), last)
                            && (ytd == null || k.days() > ytd.days())) ytd = k;
                if (ytd == null) continue;
                Double prior = null;
                Span priorSpan = null;
                for (Map.Entry<Span, Double> e : p.entrySet()) {
                    Span k = e.getKey();
                    if (k.start() != null && Math.abs(k.days() - ytd.days()) <= 10
                            && Math.abs(ChronoUnit.DAYS.between(k.end(), ytd.end()) - 365) <= 10) {
                        prior = e.getValue();
                        priorSpan = k;
                        break;
                    }
                }
                if (prior == null) continue;
                LocalDate fyEnd = ytd.start().minusDays(1);
                Double year = annual(List.of(c), unit, fyEnd);
                if (year == null) year = annual(chain, unit, fyEnd);
                if (year == null) continue;
                // A stock split since the 10-K: this year's 10-Qs restate last year's comparative per
                // share, but the fiscal year still stands pre-split in the 10-K (Alphabet 2022: P/E 1).
                // The comparative's restatement gives the split ratio.
                if (unit.equals("USD/shares")) {
                    Double first = firstFiled(c, unit, priorSpan);
                    if (first != null && first != 0) {
                        double r = prior / first;
                        if (r > 1.5 || r < 1 / 1.5) year *= r;
                    }
                }
                return new Ttm(year + p.get(ytd) - prior, ytd.end(), c);
            }
            return null;
        }

        /** The tag has year-to-date figures inside the fiscal year ending then: it is on the 10-Qs, not only in a note. */
        static boolean usedInQuarters(Map<Span, Double> p, LocalDate fyEnd) {
            for (Span k : p.keySet())
                if (k.start() != null && k.days() >= 80 && k.days() <= 290 && k.end().isBefore(fyEnd)
                        && k.end().isAfter(fyEnd.minusDays(365))) return true;
            return false;
        }

        /** A three-month figure ending at the latest period (share counts). */
        Double quarterAt(List<String> chain, String unit, LocalDate last) {
            for (String c : chain)
                for (Map.Entry<Span, Double> e : periods(c, unit).entrySet()) {
                    Span k = e.getKey();
                    if (k.start() != null && k.days() >= 80 && k.days() <= 100 && Math.abs(ChronoUnit.DAYS.between(k.end(), last)) <= 7)
                        return e.getValue();
                }
            return null;
        }

        /** The first tag in the chain with a balance-sheet figure within tol days of the date (the nearest one). */
        Point instantNear(List<String> chain, LocalDate date, int tol) {
            for (String c : chain) {
                Point best = null;
                for (Map.Entry<Span, Double> e : periods(c, "USD").entrySet()) {
                    Span k = e.getKey();
                    if (k.start() != null) continue;
                    long d = Math.abs(ChronoUnit.DAYS.between(k.end(), date));
                    if (d <= tol && (best == null || d < Math.abs(ChronoUnit.DAYS.between(best.date, date)))) best = new Point(e.getValue(), k.end());
                }
                if (best != null) return best;
            }
            return null;
        }

        /** The most recent balance-sheet figure on or before the date, in any tag of the chain. */
        Point latestInstant(List<String> chain, LocalDate date) {
            Point best = null;
            for (String c : chain)
                for (Map.Entry<Span, Double> e : periods(c, "USD").entrySet()) {
                    Span k = e.getKey();
                    if (k.start() == null && !k.end().isAfter(date) && (best == null || k.end().isAfter(best.date)))
                        best = new Point(e.getValue(), k.end());
                }
            return best;
        }

        /** Fiscal-year ends of a tag's annual figures. */
        TreeSet<LocalDate> annualEnds(String concept) {
            TreeSet<LocalDate> out = new TreeSet<>();
            for (Span k : periods(concept, "USD").keySet())
                if (k.start() != null && k.days() >= 350 && k.days() <= 380) out.add(k.end());
            return out;
        }

        /** The fiscal year ending on that date, from the first tag in the chain that has it. */
        Double annual(List<String> chain, String unit, LocalDate end) {
            for (String c : chain)
                for (Map.Entry<Span, Double> e : periods(c, unit).entrySet())
                    if (isYear(e.getKey()) && Math.abs(ChronoUnit.DAYS.between(e.getKey().end(), end)) <= 3)
                        return e.getValue();
            return null;
        }
    }

    // ------------------------------------------------------------------ SEC downloads

    /**
     * data.sec.gov, politely: the contact in every request's User-Agent as the SEC asks, well under
     * its 10 requests a second, gzip, and a cache so a rerun in the same week downloads nothing.
     */
    static final class Sec {
        static final int FACTS_DAYS = 3, SUBMISSIONS_DAYS = 30, TICKERS_DAYS = 7;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        final String agent;
        final Path cache;
        long lastRequest;

        Sec(String contact, Path cache) throws IOException {
            this.agent = contact + " PutScreener/" + PutScreener.VERSION;
            this.cache = cache;
            Files.createDirectories(cache);
        }

        /** SEC ticker ("BRK-B") -> CIK. */
        @SuppressWarnings("unchecked")
        Map<String, Integer> tickerMap() throws IOException, InterruptedException {
            Map<String, Object> root = (Map<String, Object>) Json.parse(get("https://www.sec.gov/files/company_tickers.json",
                    "company_tickers.json.gz", TICKERS_DAYS));
            Map<String, Integer> out = new HashMap<>();
            for (Object o : root.values()) {
                Map<String, Object> e = (Map<String, Object>) o;
                out.putIfAbsent(String.valueOf(e.get("ticker")).toUpperCase(Locale.ROOT), ((Double) e.get("cik_str")).intValue());
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> facts(int cik) throws IOException, InterruptedException {
            String id = String.format("CIK%010d", cik);
            return (Map<String, Object>) Json.parse(get("https://data.sec.gov/api/xbrl/companyfacts/" + id + ".json",
                    "facts_" + id + ".json.gz", FACTS_DAYS));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> submissions(int cik) throws IOException, InterruptedException {
            String id = String.format("CIK%010d", cik);
            return (Map<String, Object>) Json.parse(get("https://data.sec.gov/submissions/" + id + ".json",
                    "submissions_" + id + ".json.gz", SUBMISSIONS_DAYS));
        }

        /**
         * The reply's text, from the cache while it is fresh. A reply is cached only once it has
         * unzipped to JSON, and through a temporary file, so a run cut short can't leave a broken
         * file behind; one that is broken anyway is downloaded again.
         */
        String get(String url, String name, int maxAgeDays) throws IOException, InterruptedException {
            Path f = cache.resolve(name);
            if (Files.isRegularFile(f) && Files.getLastModifiedTime(f).toInstant()
                    .isAfter(java.time.Instant.now().minus(Duration.ofDays(maxAgeDays)))) {
                try {
                    String text = gunzip(Files.readAllBytes(f));
                    if (text.stripLeading().startsWith("{")) return text;
                } catch (IOException e) {
                    // broken cache file: fetch it again
                }
                Files.deleteIfExists(f);
            }
            IOException last = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                long wait = lastRequest + 150 - System.currentTimeMillis();   // under 7 a second
                if (wait > 0) Thread.sleep(wait);
                if (attempt > 0) Thread.sleep(2_000L * attempt);
                lastRequest = System.currentTimeMillis();
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", agent)
                        .header("Accept-Encoding", "gzip").timeout(Duration.ofSeconds(60)).build();
                HttpResponse<byte[]> resp;
                try {
                    resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                } catch (IOException e) {
                    last = e;
                    continue;
                }
                int code = resp.statusCode();
                if (code == 200) {
                    byte[] body = resp.body();
                    boolean gz = resp.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip");
                    byte[] stored = gz ? body : gzip(body);
                    String text;
                    try {
                        text = gunzip(stored);
                    } catch (IOException e) {
                        last = new IOException("a broken reply from " + url);
                        continue;
                    }
                    if (!text.stripLeading().startsWith("{")) {      // an error page served with 200
                        last = new IOException("the SEC sent something other than data from " + url);
                        continue;
                    }
                    Path tmp = cache.resolve(name + ".tmp");
                    Files.write(tmp, stored);
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                    return text;
                }
                if (code == 403) throw new IOException("the SEC refused the request (HTTP 403). Check sec_contact:"
                        + " a name and a real email, e.g. \"Jane Doe jane@example.com\"");
                if (code == 404) throw new IOException("the SEC has no " + name.replace(".gz", "") + " (HTTP 404)");
                last = new IOException("HTTP " + code + " from " + url);
                if (code != 429 && code < 500) break;
            }
            throw last;
        }

        static byte[] gzip(byte[] b) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream z = new GZIPOutputStream(out)) { z.write(b); }
            return out.toByteArray();
        }

        static String gunzip(byte[] b) throws IOException {
            try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(b))) {
                return new String(z.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    // ------------------------------------------------------------------ JSON

    /** Just enough JSON for the SEC's replies: objects, arrays, strings, numbers (as Double), true/false/null. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String s) {
            Json j = new Json(s);
            j.ws();
            return j.value();
        }

        private Object value() {
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': i += 4; return Boolean.TRUE;
                case 'f': i += 5; return Boolean.FALSE;
                case 'n': i += 4; return null;
                default: return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new HashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(i++) != ':') throw new IllegalStateException("JSON: ':' expected at " + (i - 1));
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalStateException("JSON: ',' or '}' expected at " + (i - 1));
            }
        }

        private List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw new IllegalStateException("JSON: ',' or ']' expected at " + (i - 1));
            }
        }

        private String string() {
            if (s.charAt(i++) != '"') throw new IllegalStateException("JSON: string expected at " + (i - 1));
            int start = i;
            StringBuilder b = null;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b == null ? s.substring(start, i - 1) : b.toString();
                if (c == '\\') {
                    if (b == null) b = new StringBuilder(s.substring(start, i - 1));
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                } else if (b != null) {
                    b.append(c);
                }
            }
        }

        private Double number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalStateException("JSON: value expected at " + i);
            return Double.parseDouble(s.substring(start, i));
        }

        private void ws() {
            while (i < s.length() && s.charAt(i) <= ' ') i++;
        }
    }
}
