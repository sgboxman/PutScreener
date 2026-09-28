package putscreener;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The screen as a table that fills in name by name. Rows are coloured by verdict; the capital
 * box at the top recalculates the Contracts column; a label says whether the scan used live
 * quotes or, after hours, the last close's, and another whether IB is sending live or delayed
 * data (both set by the scan, not the user); Re-scan runs the screen again; the mid-fill box
 * switches at once between the two scorings every scan makes (at the mid with the spread
 * filter, or at the assumed fill without it). The Pick column marks the puts the user likes:
 * a tick belongs to the option (symbol, expiry, strike), so it survives sorting, the mid-fill
 * box, a Re-scan and a restart (picks.txt next to the settings). All public methods are safe to
 * call from the screening thread.
 */
class ScreenerWindow {

    static final Color MERIT = new Color(198, 239, 206);   // green
    static final Color MERIT_MID = new Color(170, 218, 240); // blue: passes only at the assumed fill
    static final Color WIDE = new Color(255, 235, 156);    // amber
    static final Color IV_RV = new Color(228, 228, 228);   // grey
    static final Color NO_EDGE = new Color(255, 199, 206); // red

    // Edge Φ = edge at the mid / cash secured (strike x 100), in %, x EDGE_FACTOR; Score = edge / tail x SCORE_FACTOR.
    // Andrew's scaling (2026-09-26): the raw values were too small to read at a glance. The results CSV keeps them raw.
    // Co. Score = the company score from company_scores.csv, blank without one.
    static final double EDGE_FACTOR = 66, SCORE_FACTOR = 800;
    private static final String[] COLS = {"Pick", "Symbol", "Co. Score", "Expiry", "Spot", "Strike", "Delta", "Bid", "Ask",
            "IV %", "IV/RV", "Edge@Mid $", "Edge Φ", "Tail $", "Score", "Spread %", "Prem %", "Div",
            "Contracts", "Verdict"};
    private static final String[] FMT = {null, null, "%.0f", null, "%.2f", "%.2f", "%.2f", "%.2f", "%.2f",
            "%.0f", "%.2f", "%.0f", "%.1f", "%.0f", "%.1f", "%.1f", "%.2f", "%.2f", null, null};
    private static final int PICK = 0, SYM = 1, CO = 2, EXPIRY = 3, DIV = 17, CONTRACTS = 18, VERDICT = 19;

    private final List<PutScreener.Row> rows = new ArrayList<>();       // at the mid, spread filter on
    private final List<PutScreener.Row> fillRows = new ArrayList<>();   // at the assumed fill, no spread filter
    private volatile double capital;
    private final boolean delayedAtStart;
    private boolean midFill;          // EDT only
    private double fillAt = 0.5;      // EDT only
    private int warnEnd;              // EDT only: warnings sit above this offset in the notes box
    private volatile Runnable onRescan = () -> { };
    private JFrame frame;
    private JLabel status, mode, data;
    private JProgressBar progress;
    private JTextArea notes;
    private JCheckBox midBox;
    private JTextField cap;
    private JButton rescan;
    private Model model;
    private final Path picksFile;
    private final Set<String> picks = new LinkedHashSet<>();          // EDT only once the window is up

    ScreenerWindow(double capital, int total, boolean midFill, boolean delayed, Path picksFile) throws Exception {
        this.capital = capital;
        this.midFill = midFill;
        this.delayedAtStart = delayed;
        this.picksFile = picksFile;
        loadPicks();
        SwingUtilities.invokeAndWait(() -> build(total));
    }

    /** A put's identity for its tick: the same option in any scan, sorted anyhow, at the mid or at the fill. */
    static String pickKey(PutScreener.Row r) {
        return r.sym() + "|" + r.expiry() + "|" + String.format(Locale.ROOT, "%.2f", r.strike());
    }

    /** The saved ticks, less any whose option has expired. */
    private void loadPicks() {
        if (picksFile == null || !Files.isRegularFile(picksFile)) return;
        String today = LocalDate.now(PutScreener.NY).format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            for (String line : Files.readAllLines(picksFile, StandardCharsets.UTF_8)) {
                String[] f = line.trim().split("\\|");
                if (f.length == 3 && f[1].length() == 8 && f[1].compareTo(today) >= 0) picks.add(line.trim());
            }
        } catch (IOException e) {
            System.out.println("Couldn't read " + picksFile + ": " + e);
        }
    }

    /** Written at every tick, so nothing is lost however the window is closed. */
    private void savePicks() {
        if (picksFile == null) return;
        try {
            Files.write(picksFile, picks, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("Couldn't save " + picksFile + ": " + e);
        }
    }

    /** The rows on show: EDT only. */
    private List<PutScreener.Row> shown() { return midFill ? fillRows : rows; }

    double capital() { return capital; }

    /** Which quotes the scan uses: live, or after hours the last close's. Set by the scan, not the user. */
    void setMode(String s) { SwingUtilities.invokeLater(() -> mode.setText(s)); }

    /** Live or delayed data: set by the scan (and by the settings' delayed_data), not the user. */
    void setDelayed(boolean delayed) { SwingUtilities.invokeLater(() -> showData(delayed)); }

    private void showData(boolean delayed) {
        data.setText(delayed ? "Delayed data" : "Live data");
        data.setForeground(delayed ? new Color(190, 90, 0) : UIManager.getColor("Label.foreground"));
    }

    /** The settings file's capital, when it changed since the last scan. */
    void setCapital(double c) {
        capital = c;
        SwingUtilities.invokeLater(() -> {
            cap.setText(String.format(Locale.US, "%,.0f", c));
            model.fireTableDataChanged();
        });
    }

    void onRescan(Runnable r) { onRescan = r; }

    /** Clears the table and notes and locks the controls until scanFinished. */
    void scanStarting(int total, double fillAt) {
        SwingUtilities.invokeLater(() -> {
            rows.clear();
            fillRows.clear();
            this.fillAt = fillAt;
            midBox.setText(fillAt == 0.5 ? "Fill at mid" : String.format(Locale.US, "Fill at %.0f%% of spread", fillAt * 100));
            model.fireTableDataChanged();
            notes.setText("");
            warnEnd = 0;
            progress.setMaximum(total);
            progress.setValue(0);
            setControls(false);
        });
    }

    void scanFinished() { SwingUtilities.invokeLater(() -> setControls(true)); }

    private void setControls(boolean on) {
        rescan.setEnabled(on);
    }

    /** A warning: kept at the top of the message box, above the per-name notes, in the order raised. */
    void warn(String s) {
        SwingUtilities.invokeLater(() -> {
            String line = "WARNING  " + s + "\n";
            notes.insert(line, warnEnd);
            warnEnd += line.length();
            notes.setCaretPosition(0);
        });
    }

    void status(String s) { status(s, null); }

    /** The status line, and what hovering over it shows (the saved CSV's path at the end of a scan). */
    void status(String s, String tip) {
        SwingUtilities.invokeLater(() -> {
            status.setText(s);
            status.setToolTipText(tip);
        });
    }

    void progress(int done) { SwingUtilities.invokeLater(() -> progress.setValue(done)); }

    void addRows(List<PutScreener.Row> atMid, List<PutScreener.Row> atFill) {
        List<PutScreener.Row> a = new ArrayList<>(atMid), f = new ArrayList<>(atFill);
        SwingUtilities.invokeLater(() -> {
            rows.addAll(a);
            rows.sort(PutScreener.ORDER);
            fillRows.addAll(f);
            fillRows.sort(PutScreener.ORDER);
            model.fireTableDataChanged();
        });
    }

    void note(String s) { SwingUtilities.invokeLater(() -> notes.append(s + "\n")); }

    private void build(int total) {
        frame = new JFrame("Put Screener v" + PutScreener.VERSION + "   " + PutScreener.COPYRIGHT);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Top: status, progress, capital
        status = new JLabel("Starting...");
        progress = new JProgressBar(0, total);
        progress.setStringPainted(true);
        // Always US grouping: in German and similar settings "%,.0f" gives "35.000", which the
        // comma-stripping parse below would read as 35
        cap = new JTextField(String.format(Locale.US, "%,.0f", capital), 9);
        cap.setHorizontalAlignment(JTextField.RIGHT);
        Runnable apply = () -> {
            try {
                capital = Double.parseDouble(cap.getText().replace(",", "").replace("$", "").trim());
                cap.setText(String.format(Locale.US, "%,.0f", capital));
                model.fireTableDataChanged();
            } catch (NumberFormatException e) {
                cap.setText(String.format(Locale.US, "%,.0f", capital));
            }
        };
        cap.addActionListener(e -> apply.run());
        cap.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { apply.run(); }
        });
        mode = new JLabel(" ");
        mode.setToolTipText("<html>During market hours the scan uses live quotes. Outside them live option quotes are"
                + " empty, so it uses the last close's quotes, with time and the stock price taken at that close.<br>"
                + "Between 09:00 and 09:30 ET IB has no option quotes at all.</html>");
        data = new JLabel();
        showData(delayedAtStart);
        data.setToolTipText("<html>Live data needs an options (OPRA) subscription. Without one the scan switches by itself to"
                + " IB's free delayed data<br>(about 15 minutes old; after hours, the close) and says so in the warnings."
                + " IB sends no dividend data with delayed quotes.<br>delayed_data = true in putscreener.properties uses"
                + " delayed data always.</html>");
        rescan = new JButton("Re-scan");
        rescan.setToolTipText("Re-read putscreener.properties and run the screen again");
        rescan.addActionListener(e -> {
            setControls(false);
            onRescan.run();
        });
        setControls(false);
        midBox = new JCheckBox("Fill at mid", midFill);
        midBox.setToolTipText("<html>Ticked: each put is priced at bid + fill_at x (ask - bid), 0.5 = the mid,"
                + " and a wide spread no longer fails it.<br>A put that passes only this way shows MERIT@MID in blue."
                + " Switches at once, no re-scan. Set fill_at in putscreener.properties.</html>");
        midBox.addActionListener(e -> {
            midFill = midBox.isSelected();
            model.fireTableDataChanged();
        });
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(mode);
        right.add(Box.createHorizontalStrut(8));
        right.add(data);
        right.add(Box.createHorizontalStrut(8));
        right.add(midBox);
        right.add(rescan);
        right.add(Box.createHorizontalStrut(16));
        right.add(new JLabel("Capital $"));
        right.add(cap);
        right.add(progress);
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        top.add(status, BorderLayout.CENTER);
        top.add(right, BorderLayout.EAST);

        // Centre: the table
        model = new Model();
        JTable table = new JTable(model);
        // The Verdict column sorts best first, as the scan lists them, not alphabetically (which put
        // IV~RV at the top); equal verdicts keep the scan's order, best score first
        TableRowSorter<Model> sorter = new TableRowSorter<>(model);
        sorter.setComparator(VERDICT, Comparator.comparingInt((Object v) -> verdictRank(v.toString())));
        sorter.setComparator(PICK, Comparator.comparing((Object v) -> !(Boolean) v));   // ticked first
        table.setRowSorter(sorter);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        Renderer r = new Renderer();
        for (int c = 0; c < COLS.length; c++) table.getColumnModel().getColumn(c).setCellRenderer(r);
        table.getColumnModel().getColumn(PICK).setCellRenderer(new PickRenderer());
        table.getColumnModel().getColumn(PICK).setMaxWidth(45);

        // Bottom: skipped names and warnings, and the legend
        notes = new JTextArea(5, 40);
        notes.setEditable(false);
        notes.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        legend.add(swatch(MERIT, "MERIT: edge, IV well above RV, tight spread"));
        legend.add(swatch(MERIT_MID, "MERIT@MID: passes only if filled at the mid"));
        legend.add(swatch(WIDE, "WIDE: spread too wide"));
        legend.add(swatch(IV_RV, "IV~RV: implied not far enough above realized"));
        legend.add(swatch(NO_EDGE, "NO EDGE"));
        JPanel explain = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        explain.add(new JLabel("Pick: tick the puts you like; the Pick header brings them to the top."
                + "   (div) = ex-dividend before expiry; Div = dividend's share of the premium, per share."
                + "   Contracts = capital / (strike x 100); grey 0 = can't afford one."
                + "   Co. Score = company score (0-100) from company_scores.csv."));
        JPanel legends = new JPanel(new GridLayout(2, 1));
        legends.add(legend);
        legends.add(explain);
        JScrollPane ns = new JScrollPane(notes);
        ns.setBorder(BorderFactory.createTitledBorder("Warnings and skipped names"));

        // Table over messages with a draggable divider; extra window height goes to the table
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), ns);
        split.setResizeWeight(1.0);
        split.setOneTouchExpandable(true);
        split.setContinuousLayout(true);

        frame.add(top, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(legends, BorderLayout.SOUTH);
        frame.setSize(1450, 780);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        split.setDividerLocation(0.82);
    }

    /** Best first: the order of the legend and of the colours. */
    static int verdictRank(String v) {
        return switch (v) {
            case "MERIT" -> 0;
            case "MERIT@MID" -> 1;
            case "WIDE" -> 2;
            case "IV~RV" -> 3;
            default -> 4;                                  // NO EDGE
        };
    }

    static Color verdictColor(String v) {
        return switch (v) {
            case "MERIT" -> MERIT;
            case "MERIT@MID" -> MERIT_MID;
            case "WIDE" -> WIDE;
            case "IV~RV" -> IV_RV;
            default -> NO_EDGE;
        };
    }

    private static JLabel swatch(Color c, String text) {
        JLabel l = new JLabel(" " + text + " ");
        l.setOpaque(true);
        l.setBackground(c);
        l.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        return l;
    }

    private class Model extends AbstractTableModel {
        @Override public int getRowCount() { return shown().size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override public Class<?> getColumnClass(int c) {
            return switch (c) {
                case PICK -> Boolean.class;
                case SYM, EXPIRY, VERDICT -> String.class;
                case CONTRACTS -> Integer.class;
                default -> Double.class;
            };
        }

        @Override public boolean isCellEditable(int i, int c) { return c == PICK; }

        @Override public void setValueAt(Object v, int i, int c) {
            if (c != PICK) return;
            String key = pickKey(shown().get(i));
            if (Boolean.TRUE.equals(v)) picks.add(key); else picks.remove(key);
            savePicks();
            fireTableCellUpdated(i, c);
        }

        @Override public Object getValueAt(int i, int c) {
            PutScreener.Row r = shown().get(i);
            return switch (c) {
                case PICK -> picks.contains(pickKey(r));
                case SYM -> r.dividend() > 0 ? r.sym() + "  (div)" : r.sym();
                case CO -> PutScreener.companyScore(r.sym());
                case EXPIRY -> r.expiry();
                case 4 -> r.spot();
                case 5 -> r.strike();
                case 6 -> r.delta();
                case 7 -> r.bid();
                case 8 -> r.ask();
                case 9 -> r.iv() * 100;
                case 10 -> r.ivRv();
                case 11 -> r.edge() * 100;
                case 12 -> r.edge() / r.strike() * 100 * EDGE_FACTOR;
                case 13 -> r.tail() * 100;
                case 14 -> r.score() * SCORE_FACTOR;
                case 15 -> r.spreadPct();
                case 16 -> r.premPct();
                case DIV -> r.divPart();                   // per share, like Bid and Ask (Andrew, 09-26)
                case CONTRACTS -> PutScreener.contracts(capital, r.strike());
                default -> r.verdict();
            };
        }
    }

    private class Renderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                                 boolean focus, int row, int col) {
            int c = t.convertColumnIndexToModel(col);
            String text = v == null ? "" : (FMT[c] != null && v instanceof Double d) ? String.format(FMT[c], d) : v.toString();
            super.getTableCellRendererComponent(t, text, sel, focus, row, col);
            PutScreener.Row r = shown().get(t.convertRowIndexToModel(row));
            setHorizontalAlignment(c == SYM || c == EXPIRY || c == VERDICT ? LEFT : RIGHT);
            boolean none = c == CONTRACTS && v instanceof Integer n && n == 0;
            boolean bold = c == SYM || c == VERDICT || (c == DIV && r.dividend() > 0)
                    || (c == CONTRACTS && !none && r.verdict().startsWith("MERIT"));
            setFont(getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN));
            String tip = null;
            if (c == SYM && r.dividend() > 0) tip = r.divNote();
            if (c == VERDICT && r.verdict().equals("MERIT@MID"))
                tip = String.format("Priced at %.2f = bid + %.0f%% of the spread. With the spread filter on it would be WIDE.",
                        r.price(), fillAt * 100);
            setToolTipText(tip);
            if (c == DIV && r.dividend() == 0) setText("");
            if (!sel) {
                setBackground(verdictColor(r.verdict()));
                setForeground(none ? new Color(150, 150, 150) : Color.BLACK);
            }
            return this;
        }
    }

    /** The Pick column: a checkbox on the row's verdict colour. */
    private class PickRenderer extends JCheckBox implements TableCellRenderer {
        PickRenderer() {
            setHorizontalAlignment(CENTER);
            setBorderPainted(false);
            setOpaque(true);
            setToolTipText("Tick the puts you like. Ticks are kept across Re-scan and when the window is closed.");
        }

        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                                 boolean focus, int row, int col) {
            setSelected(Boolean.TRUE.equals(v));
            setBackground(sel ? t.getSelectionBackground() : verdictColor(shown().get(t.convertRowIndexToModel(row)).verdict()));
            return this;
        }
    }
}
