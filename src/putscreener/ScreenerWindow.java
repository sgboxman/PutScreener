package putscreener;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The screen as a table that fills in name by name. Rows are coloured by verdict; the capital
 * box at the top recalculates the Contracts column; a label says whether the scan used live
 * quotes or, after hours, the last close's; Re-scan runs the screen again; the mid-fill box
 * switches at once between the two scorings every scan makes (at the mid with the spread
 * filter, or at the assumed fill without it). All public methods are safe to call from the
 * screening thread.
 */
class ScreenerWindow {

    static final Color MERIT = new Color(198, 239, 206);   // green
    static final Color MERIT_MID = new Color(170, 218, 240); // blue: passes only at the assumed fill
    static final Color WIDE = new Color(255, 235, 156);    // amber
    static final Color IV_RV = new Color(228, 228, 228);   // grey
    static final Color NO_EDGE = new Color(255, 199, 206); // red

    // Edge % = edge at the mid / cash secured (strike x 100): expected profit per dollar tied up
    private static final String[] COLS = {"Symbol", "Expiry", "Spot", "Strike", "Delta", "Bid", "Ask",
            "IV %", "IV/RV", "Edge@Mid $", "Edge %", "Tail $", "Score", "Spread %", "Prem %", "Div $",
            "Contracts", "Verdict"};
    private static final String[] FMT = {null, null, "%.2f", "%.2f", "%.2f", "%.2f", "%.2f",
            "%.0f", "%.2f", "%.0f", "%.3f", "%.0f", "%.3f", "%.1f", "%.2f", "%.0f", null, null};
    private static final int SYM = 0, DIV = 15, CONTRACTS = 16, VERDICT = 17;

    private final List<PutScreener.Row> rows = new ArrayList<>();       // at the mid, spread filter on
    private final List<PutScreener.Row> fillRows = new ArrayList<>();   // at the assumed fill, no spread filter
    private volatile double capital;
    private volatile boolean delayed;
    private boolean midFill;          // EDT only
    private double fillAt = 0.5;      // EDT only
    private int warnEnd;              // EDT only: warnings sit above this offset in the notes box
    private volatile Runnable onRescan = () -> { };
    private JFrame frame;
    private JLabel status, mode;
    private JProgressBar progress;
    private JTextArea notes;
    private JCheckBox delayedBox;
    private JCheckBox midBox;
    private JTextField cap;
    private JButton rescan;
    private Model model;

    ScreenerWindow(double capital, int total, boolean midFill, boolean delayed) throws Exception {
        this.capital = capital;
        this.midFill = midFill;
        this.delayed = delayed;
        SwingUtilities.invokeAndWait(() -> build(total));
    }

    /** The rows on show: EDT only. */
    private List<PutScreener.Row> shown() { return midFill ? fillRows : rows; }

    double capital() { return capital; }

    boolean delayed() { return delayed; }

    /** Which quotes the scan uses: live, or after hours the last close's. Set by the scan, not the user. */
    void setMode(String s) { SwingUtilities.invokeLater(() -> mode.setText(s)); }

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
        delayedBox.setEnabled(on);
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

    void status(String s) { SwingUtilities.invokeLater(() -> status.setText(s)); }

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
        delayedBox = new JCheckBox("Delayed data", delayed);
        delayedBox.setToolTipText("<html>Ticked: IB's free delayed data (~15 minutes old; after hours, the close), for"
                + " accounts without an options (OPRA) subscription.<br>Price history still needs a US stock"
                + " subscription, and IB sends no dividend data on delayed quotes.</html>");
        delayedBox.addActionListener(e -> delayed = delayedBox.isSelected());
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
        right.add(midBox);
        right.add(delayedBox);
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
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        Renderer r = new Renderer();
        for (int c = 0; c < COLS.length; c++) table.getColumnModel().getColumn(c).setCellRenderer(r);

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
        explain.add(new JLabel("(div) = ex-dividend before expiry; Div $ = dividend's share of the premium."
                + "   Contracts = capital / (strike x 100); grey 0 = can't afford one."));
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
                case SYM, 1, VERDICT -> String.class;
                case CONTRACTS -> Integer.class;
                default -> Double.class;
            };
        }

        @Override public Object getValueAt(int i, int c) {
            PutScreener.Row r = shown().get(i);
            return switch (c) {
                case SYM -> r.dividend() > 0 ? r.sym() + "  (div)" : r.sym();
                case 1 -> r.expiry();
                case 2 -> r.spot();
                case 3 -> r.strike();
                case 4 -> r.delta();
                case 5 -> r.bid();
                case 6 -> r.ask();
                case 7 -> r.iv() * 100;
                case 8 -> r.ivRv();
                case 9 -> r.edge() * 100;
                case 10 -> r.edge() / r.strike() * 100;
                case 11 -> r.tail() * 100;
                case 12 -> r.score();
                case 13 -> r.spreadPct();
                case 14 -> r.premPct();
                case DIV -> r.divPart() * 100;
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
            setHorizontalAlignment(c == SYM || c == 1 || c == VERDICT ? LEFT : RIGHT);
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
                setBackground(switch (r.verdict()) {
                    case "MERIT" -> MERIT;
                    case "MERIT@MID" -> MERIT_MID;
                    case "WIDE" -> WIDE;
                    case "IV~RV" -> IV_RV;
                    default -> NO_EDGE;
                });
                setForeground(none ? new Color(150, 150, 150) : Color.BLACK);
            }
            return this;
        }
    }
}
