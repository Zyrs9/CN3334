import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.text.*;
import java.util.*;
import java.util.List;

/**
 * VisualOversee — Real-time Swing dashboard for the LoadBalancer cluster.
 * New in this version:
 * • 🚀 Process Control tab – start/stop Server &amp; Client instances via
 * ProcessBuilder
 * • 🕐 Server Timeline tab – Gantt chart showing server up/down history (5-min
 * window)
 * • 📈 RTT chart, 🛠 Admin command, alerts, refresh slider (all from previous
 * version)
 *
 * Usage: java VisualOversee [lb-host] [status-port] [command-port]
 * [project-dir]
 * Defaults: localhost 11116 11117 &lt;current dir&gt;
 */
public class VisualOversee extends JFrame {

    // ── connection ────────────────────────────────────────────────────────────
    private final String lbHost;
    private final int statusPort, commandPort;
    private static File projectDir = new File(System.getProperty("user.dir"));

    // ── palette ───────────────────────────────────────────────────────────────
    static final Color BG = new Color(0x0f, 0x0f, 0x1a), PANEL_BG = new Color(0x16, 0x16, 0x2e);
    static final Color CARD_BG = new Color(0x1e, 0x1e, 0x3f), FG = new Color(0xe2, 0xe8, 0xf0);
    static final Color MUTED = new Color(0x94, 0xa3, 0xb8), ACCENT = new Color(0x38, 0xbd, 0xf8);
    static final Color GREEN = new Color(0x34, 0xd3, 0x99), YELLOW = new Color(0xfb, 0xbf, 0x24);
    static final Color RED = new Color(0xf8, 0x71, 0x71), ALERT_BG = new Color(0x3a, 0x10, 0x10);
    static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    static final Font BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    static final Font SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

    // ── status bar ─────────────────────────────────────────────────────────────
    private final JLabel lblStatus = pill("● CONNECTING", YELLOW), lblUptime = info("Uptime: —");
    private final JLabel lblMode = info("Mode: —"), lblPing = info("Ping: —");
    private final JLabel lblMaxconn = info("MaxConn: —"), lblBans = info("Bans: —");
    private final JLabel lblEvict = info("Evict: —"), lblTime = info("");

    // ── server table ───────────────────────────────────────────────────────────
    private final DefaultTableModel srvModel = noEdit("Server", "RTT", "Health", "Req", "Live", "Status");
    private final JTable srvTable = new JTable(srvModel);

    // ── RTT history (rolling) ──────────────────────────────────────────────────
    private static final int RTT_HIST = 60;
    private final Map<String, Deque<Integer>> rttHistory = new LinkedHashMap<>();
    private final RttChartPanel rttChart = new RttChartPanel();

    // ── Timeline tracking ──────────────────────────────────────────────────────
    private static final long TL_WINDOW = 5 * 60_000L; // 5 minutes
    private final Map<String, Long> srvFirstSeen = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Long> srvLeftAt = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile Set<String> currentSrvKeys = new HashSet<>();
    private final ServerTimelinePanel timelinePanel = new ServerTimelinePanel();

    // ── live clients ────────────────────────────────────────────────────────────
    private final DefaultTableModel cliModel = noEdit("Client", "IP", "Server");
    private final JTable cliTable = new JTable(cliModel);

    // ── assignments ─────────────────────────────────────────────────────────────
    private final DefaultListModel<String> assignModel = new DefaultListModel<>();
    private final JList<String> assignList = new JList<>(assignModel);

    // ── admin ───────────────────────────────────────────────────────────────────
    private final JTextField adminInput = new JTextField();
    private final JTextArea adminOut = new JTextArea(4, 40);

    // ── process control ─────────────────────────────────────────────────────────
    private final List<ManagedProcess> procs = Collections.synchronizedList(new ArrayList<>());
    private final DefaultTableModel procModel = noEdit("Type", "Name", "Status", "Age");
    private final JTable procTable = new JTable(procModel);
    private final JTextArea procLog = new JTextArea(5, 50);
    private int srvSeq = 1, cliSeq = 1;

    // ── refresh ──────────────────────────────────────────────────────────────────
    private volatile int refreshMs = 1000;
    private javax.swing.Timer refreshTimer;

    // ─────────────────────────────────────────────────────────────────────────────
    public VisualOversee(String lbHost, int statusPort, int commandPort) {
        super("VisualOversee — Cluster Dashboard  [" + lbHost + ":" + statusPort + "]");
        this.lbHost = lbHost;
        this.statusPort = statusPort;
        this.commandPort = commandPort;
        applyDarkDefaults();
        buildUI();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1440, 900);
        setMinimumSize(new Dimension(1100, 680));
        setLocationRelativeTo(null);
        setVisible(true);
        refreshTimer = new javax.swing.Timer(refreshMs, e -> refresh());
        refreshTimer.start();
        refresh();
    }

    // ─── layout ──────────────────────────────────────────────────────────────────
    private void buildUI() {
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(6, 6));
        add(buildStatusBar(), BorderLayout.NORTH);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), buildRight());
        main.setResizeWeight(0.55);
        main.setDividerSize(4);
        main.setBackground(BG);
        main.setBorder(null);
        add(main, BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(8, 8, 8, 8));
    }

    private JPanel buildStatusBar() {
        JPanel bar = dark(new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6)));
        bar.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, CARD_BG), new EmptyBorder(4, 8, 4, 8)));
        for (JComponent c : new JComponent[] { lblStatus, sep(), lblUptime, sep(), lblMode, sep(),
                lblPing, sep(), lblMaxconn, sep(), lblBans, sep(), lblEvict })
            bar.add(c);

        JSlider slider = new JSlider(500, 10000, 1000);
        slider.setBackground(BG);
        slider.setPreferredSize(new Dimension(120, 20));
        JLabel sliderMs = info("1.0s");
        slider.addChangeListener(e -> {
            sliderMs.setText(String.format("%.1fs", slider.getValue() / 1000.0));
            if (!slider.getValueIsAdjusting()) {
                refreshMs = slider.getValue();
                refreshTimer.setDelay(refreshMs);
                refreshTimer.restart();
            }
        });
        JPanel right = dark(new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6)));
        right.add(info("Refresh:"));
        right.add(slider);
        right.add(sliderMs);
        right.add(sep());
        right.add(lblTime);
        JPanel wrap = dark(new JPanel(new BorderLayout()));
        wrap.add(bar, BorderLayout.WEST);
        wrap.add(right, BorderLayout.EAST);
        return wrap;
    }

    private JSplitPane buildLeft() {
        styleTable(srvTable);
        int[] w = { 180, 110, 65, 55, 45, 85 };
        for (int i = 0; i < w.length; i++)
            srvTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        srvTable.getColumnModel().getColumn(1).setCellRenderer(new RttBarR());
        srvTable.getColumnModel().getColumn(2).setCellRenderer(new HealthR());
        srvTable.getColumnModel().getColumn(5).setCellRenderer(new BadgeR());
        srvTable.setDefaultRenderer(Object.class, new AlertR());
        JScrollPane srvPane = scroll(srvTable, "⚙  Servers");

        JTabbedPane tabBottom = new JTabbedPane(JTabbedPane.TOP);
        tabBottom.setBackground(CARD_BG);
        tabBottom.setForeground(MUTED);
        tabBottom.addTab("  📈 RTT History  ", scrollRaw(rttChart, ""));
        rttChart.setPreferredSize(new Dimension(600, 160));
        JScrollPane tlScroll = new JScrollPane(timelinePanel);
        tlScroll.setBackground(PANEL_BG);
        tlScroll.getViewport().setBackground(PANEL_BG);
        tlScroll.setBorder(null);
        tabBottom.addTab("  🕐 Server Timeline  ", tlScroll);
        timelinePanel.setPreferredSize(new Dimension(600, 200));

        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, srvPane, tabBottom);
        sp.setResizeWeight(0.50);
        sp.setDividerSize(4);
        sp.setBackground(BG);
        sp.setBorder(null);
        return sp;
    }

    private JPanel buildRight() {
        styleTable(cliTable);
        cliTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        cliTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        cliTable.getColumnModel().getColumn(2).setPreferredWidth(160);
        assignList.setBackground(PANEL_BG);
        assignList.setForeground(FG);
        assignList.setFont(MONO);
        assignList.setFixedCellHeight(22);
        assignList.setCellRenderer(new AssignR());
        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll(cliTable, "👤  Live Clients"),
                scroll(assignList, "↪  Recent Assignments"));
        sp.setResizeWeight(0.45);
        sp.setDividerSize(4);
        sp.setBackground(BG);
        sp.setBorder(null);
        JPanel wrap = dark(new JPanel(new BorderLayout()));
        wrap.add(sp, BorderLayout.CENTER);
        return wrap;
    }

    private JTabbedPane buildBottom() {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setBackground(CARD_BG);
        tabs.setForeground(MUTED);
        tabs.setPreferredSize(new Dimension(1440, 210));
        tabs.addTab("  🛠 Admin Command  ", buildAdminTab());
        tabs.addTab("  🚀 Process Control  ", buildProcessTab());
        return tabs;
    }

    // ─── Admin tab
    // ────────────────────────────────────────────────────────────────
    private JPanel buildAdminTab() {
        adminInput.setFont(MONO);
        adminInput.setBackground(new Color(0x0a, 0x0a, 0x18));
        adminInput.setForeground(FG);
        adminInput.setCaretColor(ACCENT);
        adminInput.setBorder(new CompoundBorder(new LineBorder(CARD_BG.brighter(), 1), new EmptyBorder(4, 6, 4, 6)));
        JButton send = mkBtn("Send →", ACCENT.darker());
        send.addActionListener(e -> sendAdminCmd());
        adminInput.addActionListener(e -> sendAdminCmd());
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(CARD_BG);
        JLabel lbl = info("🛠 Admin:");
        lbl.setFont(BOLD);
        lbl.setForeground(ACCENT);
        row.add(lbl, BorderLayout.WEST);
        row.add(adminInput, BorderLayout.CENTER);
        row.add(send, BorderLayout.EAST);
        adminOut.setFont(MONO);
        adminOut.setBackground(new Color(0x0a, 0x0a, 0x18));
        adminOut.setForeground(GREEN);
        adminOut.setEditable(false);
        adminOut.setBorder(new EmptyBorder(4, 6, 4, 6));
        JScrollPane outScroll = new JScrollPane(adminOut);
        outScroll.setBorder(null);
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(CARD_BG);
        p.setBorder(new EmptyBorder(6, 8, 6, 8));
        p.add(row, BorderLayout.NORTH);
        p.add(outScroll, BorderLayout.CENTER);
        return p;
    }

    private void sendAdminCmd() {
        String cmd = adminInput.getText().trim();
        if (cmd.isEmpty())
            return;
        adminInput.setText("");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder("> " + cmd + "\n");
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(lbHost, commandPort), 2000);
                s.setSoTimeout(3000);
                new PrintWriter(s.getOutputStream(), true).println(cmd);
                BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) {
                    if ("END".equals(line))
                        break;
                    sb.append(line).append('\n');
                }
            } catch (IOException e) {
                sb.append("[Error] ").append(e.getMessage()).append('\n');
            }
            String r = sb.toString();
            SwingUtilities.invokeLater(() -> {
                adminOut.append(r);
                adminOut.setCaretPosition(adminOut.getDocument().getLength());
            });
        }, "Admin-Cmd").start();
    }

    // ─── Process Control tab
    // ──────────────────────────────────────────────────────
    private JPanel buildProcessTab() {
        // Buttons
        JButton bSrv = mkBtn("＋ Server", new Color(0x1a, 0x4a, 0x2a));
        JButton bDyn = mkBtn("＋ Dynamic", new Color(0x1a, 0x2a, 0x4a));
        JButton bSta = mkBtn("＋ Static", new Color(0x3a, 0x2a, 0x1a));
        JButton bStk = mkBtn("＋ Sticky", new Color(0x2a, 0x1a, 0x4a));
        JButton bStop = mkBtn("✕ Stop", new Color(0x4a, 0x10, 0x10));
        JButton bAll = mkBtn("✕ Stop All", new Color(0x4a, 0x10, 0x10));
        bSrv.addActionListener(e -> spawnServer());
        bDyn.addActionListener(e -> spawnClient("dynamic"));
        bSta.addActionListener(e -> spawnClient("static"));
        bStk.addActionListener(e -> spawnClient("sticky"));
        bStop.addActionListener(e -> stopSelected());
        bAll.addActionListener(e -> stopAll());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.setBackground(CARD_BG);
        for (JComponent b : new JComponent[] { bSrv, bDyn, bSta, bStk, new JSeparator(SwingConstants.VERTICAL), bStop,
                bAll })
            btns.add(b);

        // Process table
        styleTable(procTable);
        procTable.setRowHeight(22);
        procTable.getColumnModel().getColumn(0).setPreferredWidth(65);
        procTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        procTable.getColumnModel().getColumn(2).setPreferredWidth(75);
        procTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        procTable.setDefaultRenderer(Object.class, new ProcR());
        JScrollPane ptScroll = new JScrollPane(procTable);
        ptScroll.setBackground(PANEL_BG);
        ptScroll.getViewport().setBackground(PANEL_BG);
        ptScroll.setBorder(null);
        ptScroll.setPreferredSize(new Dimension(400, 90));

        // Log
        procLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        procLog.setBackground(new Color(0x0a, 0x0a, 0x18));
        procLog.setForeground(GREEN);
        procLog.setEditable(false);
        procLog.setBorder(new EmptyBorder(4, 6, 4, 6));
        JScrollPane logScroll = new JScrollPane(procLog);
        logScroll.setBorder(null);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, ptScroll, logScroll);
        split.setResizeWeight(0.45);
        split.setDividerSize(3);
        split.setBackground(CARD_BG);
        split.setBorder(null);

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(CARD_BG);
        p.setBorder(new EmptyBorder(6, 8, 6, 8));
        p.add(btns, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private void spawnServer() {
        String name = "Server-" + srvSeq++;
        spawn(ManagedProcess.PType.SERVER, name,
                new String[] { "java", "Server", "-p", "0", "-u", "0", "--lb-host", lbHost });
    }

    private void spawnClient(String mode) {
        String name = "Client-" + mode.substring(0, 3).toUpperCase() + "-" + cliSeq++;
        spawn(ManagedProcess.PType.CLIENT, name,
                new String[] { "java", "Client", "--name", name, "--mode", mode, "--lb-host", lbHost });
    }

    private void spawn(ManagedProcess.PType type, String name, String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ManagedProcess mp = new ManagedProcess(type, name, p);
            procs.add(mp);
            appendProcLog("[" + name + "] Started (" + String.join(" ", cmd) + ")");
            new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        final String l = "[" + name + "] " + line;
                        SwingUtilities.invokeLater(() -> appendProcLog(l));
                    }
                } catch (IOException ignored) {
                }
                mp.alive = false;
                SwingUtilities.invokeLater(this::refreshProcTable);
            }, "ProcOut-" + name).start();
            refreshProcTable();
        } catch (IOException e) {
            appendProcLog("[Error starting " + name + "] " + e.getMessage());
        }
    }

    private void stopSelected() {
        int row = procTable.getSelectedRow();
        if (row < 0)
            return;
        synchronized (procs) {
            if (row < procs.size()) {
                ManagedProcess mp = procs.get(row);
                mp.process.destroyForcibly();
                mp.alive = false;
                appendProcLog("[" + mp.name + "] Stopped by user.");
            }
        }
        refreshProcTable();
    }

    private void stopAll() {
        synchronized (procs) {
            for (ManagedProcess mp : procs) {
                mp.process.destroyForcibly();
                mp.alive = false;
            }
            appendProcLog("[All] Stopped " + procs.size() + " process(es).");
        }
        refreshProcTable();
    }

    private void refreshProcTable() {
        procModel.setRowCount(0);
        synchronized (procs) {
            for (ManagedProcess mp : procs) {
                mp.alive = mp.process.isAlive();
                long age = (System.currentTimeMillis() - mp.startedAt) / 1000;
                procModel.addRow(new Object[] { mp.type, mp.name, mp.alive ? "RUNNING" : "STOPPED", formatDur(age) });
            }
        }
    }

    private void appendProcLog(String line) {
        SwingUtilities.invokeLater(() -> {
            procLog.append(line + "\n");
            // Keep last 500 lines
            String text = procLog.getText();
            String[] lines = text.split("\n");
            if (lines.length > 500)
                procLog.setText(String.join("\n", Arrays.copyOfRange(lines, lines.length - 500, lines.length)) + "\n");
            procLog.setCaretPosition(procLog.getDocument().getLength());
        });
    }

    // ─── Refresh cycle
    // ────────────────────────────────────────────────────────────
    private void refresh() {
        lblTime.setText(new SimpleDateFormat("HH:mm:ss").format(new Date()));
        StatusData d = fetchStatus();
        if (d == null) {
            lblStatus.setText("● OFFLINE");
            lblStatus.setForeground(RED);
            return;
        }
        updateBar(d);
        updateServers(d);
        updateClients(d);
        updateAssignments(d);
        rttChart.repaint();
        timelinePanel.repaint();
        refreshProcTable();
    }

    private void updateBar(StatusData d) {
        lblStatus.setText("● ONLINE");
        lblStatus.setForeground(GREEN);
        lblUptime.setText("Uptime: " + formatDur((System.currentTimeMillis() - d.upSince) / 1000));
        lblMode.setText("Mode: " + d.defaultMode);
        lblPing.setText("Ping: " + d.pingIntervalMs + "ms");
        lblMaxconn.setText("MaxConn: " + (d.maxPerServer == Integer.MAX_VALUE ? "∞" : d.maxPerServer));
        lblBans.setText("Bans: " + (d.bannedIps.size() + d.bannedNames.size()));
        lblEvict.setText("Evict: " + (d.evictionTimeoutMs / 1000) + "s");
    }

    private void updateServers(StatusData d) {
        srvModel.setRowCount(0);
        Set<String> newKeys = new HashSet<>();
        for (StatusData.SrvEntry s : d.servers) {
            String key = s.addr + ":" + s.port;
            newKeys.add(key);
            // Timeline: record first seen
            srvFirstSeen.putIfAbsent(key, System.currentTimeMillis());
            srvLeftAt.remove(key); // still alive
            // RTT history
            rttHistory.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(s.rttMs);
            Deque<Integer> h = rttHistory.get(key);
            while (h.size() > RTT_HIST)
                h.pollFirst();
            srvModel.addRow(new Object[] { key, s.rttMs < 0 ? "n/a" : s.rttMs + " ms", s.healthScore + "%",
                    s.requestCount, s.liveCount, s.drained ? "DRAINED" : "ACTIVE" });
        }
        // Mark servers that disappeared
        for (String key : new ArrayList<>(srvFirstSeen.keySet()))
            if (!newKeys.contains(key) && !srvLeftAt.containsKey(key))
                srvLeftAt.put(key, System.currentTimeMillis());
        rttChart.setData(new LinkedHashMap<>(rttHistory));
        timelinePanel.setData(srvFirstSeen, srvLeftAt, newKeys);
        currentSrvKeys = newKeys;
    }

    private void updateClients(StatusData d) {
        cliModel.setRowCount(0);
        for (StatusData.SrvEntry s : d.servers) {
            String sa = s.addr + ":" + s.port;
            for (StatusData.CliEntry c : s.liveClients)
                cliModel.addRow(new Object[] { c.name, c.ip, sa });
        }
    }

    private void updateAssignments(StatusData d) {
        assignModel.clear();
        for (int i = d.assignments.size() - 1; i >= 0; i--) {
            StatusData.AssignEntry a = d.assignments.get(i);
            String tag = "dynamic".equalsIgnoreCase(a.mode) ? "[DYN]"
                    : "sticky".equalsIgnoreCase(a.mode) ? "[STK]" : "[STA]";
            assignModel.addElement(tag + " " + new SimpleDateFormat("HH:mm:ss").format(new Date(a.assignedAt)) + "  "
                    + a.clientName + "  →  " + a.server);
        }
    }

    private StatusData fetchStatus() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(lbHost, statusPort), 800);
            s.setSoTimeout(800);
            String json = new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
            return json == null ? null : JsonParser.parse(json);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── RTT Chart
    // ────────────────────────────────────────────────────────────────
    class RttChartPanel extends JPanel {
        private Map<String, Deque<Integer>> data = new LinkedHashMap<>();
        static final int MAX_RTT = 300;
        static final Color[] LINES = { new Color(0x38, 0xbd, 0xf8), new Color(0x34, 0xd3, 0x99),
                new Color(0xfb, 0xbf, 0x24), new Color(0xf8, 0x71, 0x71), new Color(0xa7, 0x8b, 0xfa),
                new Color(0xf4, 0xa2, 0x61) };

        void setData(Map<String, Deque<Integer>> d) {
            this.data = d;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            setBackground(PANEL_BG);
            int W = getWidth(), H = getHeight(), PL = 46, PR = 8, PT = 12, PB = 28, cW = W - PL - PR, cH = H - PT - PB;
            // grid
            for (int i = 0; i <= 4; i++) {
                int y = PT + (int) (cH * i / 4.0);
                g.setColor(CARD_BG);
                g.drawLine(PL, y, PL + cW, y);
                g.setColor(MUTED);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
                g.drawString(MAX_RTT * (4 - i) / 4 + "ms", 2, y + 4);
            }
            // lines
            int ci = 0, lx = PL + 4;
            for (Map.Entry<String, Deque<Integer>> e : data.entrySet()) {
                Color c = LINES[ci % LINES.length];
                Integer[] vals = e.getValue().toArray(new Integer[0]);
                g.setColor(c);
                g.fillRect(lx, PT + 2, 8, 8);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
                g.drawString(e.getKey(), lx + 10, PT + 10);
                lx += g.getFontMetrics().stringWidth(e.getKey()) + 22;
                if (vals.length < 2) {
                    ci++;
                    continue;
                }
                g.setStroke(new BasicStroke(1.5f));
                int px = -1, py = -1;
                for (int i = 0; i < vals.length; i++) {
                    if (vals[i] < 0) {
                        px = py = -1;
                        continue;
                    }
                    int x = PL + (int) ((double) i / (RTT_HIST - 1) * cW);
                    int y = PT + cH - (int) (Math.min(vals[i], MAX_RTT) / (double) MAX_RTT * cH);
                    if (px >= 0)
                        g.drawLine(px, py, x, y);
                    px = x;
                    py = y;
                }
                ci++;
            }
        }
    }

    // ─── Timeline Panel
    // ───────────────────────────────────────────────────────────
    static class ServerTimelinePanel extends JPanel {
        private Map<String, Long> firstSeen = new LinkedHashMap<>();
        private Map<String, Long> leftAt = new LinkedHashMap<>();
        private Set<String> alive = new HashSet<>();

        void setData(Map<String, Long> fs, Map<String, Long> la, Set<String> al) {
            firstSeen = new LinkedHashMap<>(fs);
            leftAt = new LinkedHashMap<>(la);
            alive = new HashSet<>(al);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            setBackground(PANEL_BG);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int W = getWidth(), H = getHeight(), PL = 185, PR = 75, PT = 16, PB = 26, cW = W - PL - PR;
            long now = System.currentTimeMillis(), winStart = now - TL_WINDOW;
            List<String> srvs = new ArrayList<>(firstSeen.keySet());
            if (srvs.isEmpty()) {
                g.setColor(MUTED);
                g.setFont(SMALL);
                g.drawString("No servers observed yet.", PL + 10, H / 2);
                return;
            }
            int rowH = Math.max(22, (H - PT - PB) / Math.max(1, srvs.size()));
            for (int i = 0; i < srvs.size(); i++) {
                String key = srvs.get(i);
                long join = firstSeen.getOrDefault(key, now);
                Long left = leftAt.get(key);
                long end = left != null ? left : now;
                int y = PT + i * rowH;
                g.setColor(left != null ? MUTED : FG);
                g.setFont(SMALL);
                String lbl = key + (left != null ? " [GONE]" : " [ACTIVE]");
                g.drawString(lbl, 4, y + rowH - 5);
                double sx = PL + Math.max(0, (double) (join - winStart) / TL_WINDOW) * cW;
                double ex = PL + Math.min(1, (double) (end - winStart) / TL_WINDOW) * cW;
                if (ex > sx && ex > PL && sx < PL + cW) {
                    sx = Math.max(PL, sx);
                    ex = Math.min(PL + cW, ex);
                    g.setColor(left != null ? new Color(0x5a, 0x30, 0x10) : GREEN.darker());
                    g.fillRoundRect((int) sx, y + 3, (int) (ex - sx), rowH - 6, 4, 4);
                    if (left != null) {
                        g.setColor(RED);
                        g.fillRect((int) ex - 2, y + 3, 3, rowH - 6);
                    }
                }
                // Age label
                long activeSecs = (end - join) / 1000;
                g.setColor(left != null ? RED : GREEN);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
                g.drawString(formatDur(activeSecs), PL + cW + 4, y + rowH - 5);
            }
            // X axis
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            g.setColor(MUTED);
            for (int i = 0; i <= 5; i++) {
                int x = PL + cW * i / 5;
                long t = winStart + (long) (TL_WINDOW * i / 5);
                g.drawString(new SimpleDateFormat("HH:mm:ss").format(new Date(t)), x - 18, H - PB + 14);
                g.setColor(CARD_BG);
                g.drawLine(x, PT, x, PT + ((H - PT - PB) / Math.max(1, srvs.size())) * srvs.size());
                g.setColor(MUTED);
            }
        }

        private static String formatDur(long s) {
            long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
            return h > 0 ? h + "h " + m + "m" : m > 0 ? m + "m " + ss + "s" : s + "s";
        }
    }

    // ─── Data model
    // ───────────────────────────────────────────────────────────────
    static class StatusData {
        long upSince;
        String defaultMode = "";
        int maxPerServer, pingIntervalMs;
        long evictionTimeoutMs;
        List<String> bannedIps = new ArrayList<>(), bannedNames = new ArrayList<>();
        List<SrvEntry> servers = new ArrayList<>();
        List<AssignEntry> assignments = new ArrayList<>();

        static class SrvEntry {
            String addr = "";
            int port, rttMs = -1, weight = 1, liveCount, healthScore = 100;
            long requestCount;
            boolean drained;
            List<CliEntry> liveClients = new ArrayList<>();
        }

        static class CliEntry {
            String name = "", ip = "";
        }

        static class AssignEntry {
            String clientName = "", mode = "", server = "";
            long assignedAt;
        }
    }

    // ─── JSON parser
    // ──────────────────────────────────────────────────────────────
    static class JsonParser {
        private final String s;
        private int pos;

        private JsonParser(String s) {
            this.s = s;
        }

        static StatusData parse(String json) {
            try {
                return new JsonParser(json.trim()).parseStatus();
            } catch (Exception e) {
                return null;
            }
        }

        private StatusData parseStatus() {
            StatusData d = new StatusData();
            expect('{');
            while (peek() != '}') {
                String k = str();
                expect(':');
                switch (k) {
                    case "upSince":
                        d.upSince = lng();
                        break;
                    case "defaultMode":
                        d.defaultMode = str();
                        break;
                    case "maxPerServer":
                        d.maxPerServer = num();
                        break;
                    case "pingIntervalMs":
                        d.pingIntervalMs = num();
                        break;
                    case "evictionTimeoutMs":
                        d.evictionTimeoutMs = lng();
                        break;
                    case "bannedIps":
                        d.bannedIps = strArr();
                        break;
                    case "bannedNames":
                        d.bannedNames = strArr();
                        break;
                    case "servers":
                        d.servers = srvs();
                        break;
                    case "recentAssignments":
                        d.assignments = assigns();
                        break;
                    default:
                        skip();
                }
                comma();
            }
            return d;
        }

        private List<StatusData.SrvEntry> srvs() {
            List<StatusData.SrvEntry> l = new ArrayList<>();
            expect('[');
            while (peek() != ']') {
                l.add(srv());
                comma();
            }
            expect(']');
            return l;
        }

        private StatusData.SrvEntry srv() {
            StatusData.SrvEntry e = new StatusData.SrvEntry();
            expect('{');
            while (peek() != '}') {
                String k = str();
                expect(':');
                switch (k) {
                    case "addr":
                        e.addr = str();
                        break;
                    case "port":
                        e.port = num();
                        break;
                    case "rttMs":
                        e.rttMs = num();
                        break;
                    case "weight":
                        e.weight = num();
                        break;
                    case "drained":
                        e.drained = bool();
                        break;
                    case "liveCount":
                        e.liveCount = num();
                        break;
                    case "requestCount":
                        e.requestCount = lng();
                        break;
                    case "healthScore":
                        e.healthScore = num();
                        break;
                    case "liveClients":
                        e.liveClients = clis();
                        break;
                    default:
                        skip();
                }
                comma();
            }
            expect('}');
            return e;
        }

        private List<StatusData.CliEntry> clis() {
            List<StatusData.CliEntry> l = new ArrayList<>();
            expect('[');
            while (peek() != ']') {
                StatusData.CliEntry c = new StatusData.CliEntry();
                expect('{');
                while (peek() != '}') {
                    String k = str();
                    expect(':');
                    if ("name".equals(k))
                        c.name = str();
                    else if ("ip".equals(k))
                        c.ip = str();
                    else
                        skip();
                    comma();
                }
                expect('}');
                l.add(c);
                comma();
            }
            expect(']');
            return l;
        }

        private List<StatusData.AssignEntry> assigns() {
            List<StatusData.AssignEntry> l = new ArrayList<>();
            expect('[');
            while (peek() != ']') {
                StatusData.AssignEntry a = new StatusData.AssignEntry();
                expect('{');
                while (peek() != '}') {
                    String k = str();
                    expect(':');
                    switch (k) {
                        case "clientName":
                            a.clientName = str();
                            break;
                        case "mode":
                            a.mode = str();
                            break;
                        case "server":
                            a.server = str();
                            break;
                        case "assignedAt":
                            a.assignedAt = lng();
                            break;
                        default:
                            skip();
                    }
                    comma();
                }
                expect('}');
                l.add(a);
                comma();
            }
            expect(']');
            return l;
        }

        private List<String> strArr() {
            List<String> l = new ArrayList<>();
            expect('[');
            while (peek() != ']') {
                l.add(str());
                comma();
            }
            expect(']');
            return l;
        }

        private String str() {
            skipW();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                } else if (c == '"')
                    break;
                else
                    sb.append(c);
            }
            return sb.toString();
        }

        private int num() {
            skipW();
            int st = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '-'))
                pos++;
            return Integer.parseInt(s.substring(st, pos));
        }

        private long lng() {
            skipW();
            int st = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '-'))
                pos++;
            return Long.parseLong(s.substring(st, pos));
        }

        private boolean bool() {
            skipW();
            if (s.startsWith("true", pos)) {
                pos += 4;
                return true;
            }
            pos += 5;
            return false;
        }

        private void skip() {
            skipW();
            char c = peek();
            if (c == '"') {
                str();
                return;
            }
            if (c == '{') {
                expect('{');
                while (peek() != '}') {
                    str();
                    expect(':');
                    skip();
                    comma();
                }
                expect('}');
                return;
            }
            if (c == '[') {
                expect('[');
                while (peek() != ']') {
                    skip();
                    comma();
                }
                expect(']');
                return;
            }
            if (c == 't') {
                pos += 4;
                return;
            }
            if (c == 'f') {
                pos += 5;
                return;
            }
            if (c == 'n') {
                pos += 4;
                return;
            }
            while (pos < s.length() && ",}]".indexOf(s.charAt(pos)) < 0)
                pos++;
        }

        private char peek() {
            skipW();
            return pos < s.length() ? s.charAt(pos) : 0;
        }

        private void expect(char c) {
            skipW();
            pos++;
        }

        private void comma() {
            skipW();
            if (pos < s.length() && s.charAt(pos) == ',')
                pos++;
        }

        private void skipW() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos)))
                pos++;
        }
    }

    // ─── Managed process ─────────────────────────────────────────────────────────
    static class ManagedProcess {
        enum PType {
            SERVER, CLIENT
        }

        final PType type;
        final String name;
        final Process process;
        final long startedAt = System.currentTimeMillis();
        volatile boolean alive = true;

        ManagedProcess(PType t, String n, Process p) {
            type = t;
            name = n;
            process = p;
        }
    }

    // ─── Renderers ───────────────────────────────────────────────────────────────
    static class AlertR extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int row, int col) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, f, row, col);
            l.setOpaque(true);
            boolean alert = false;
            try {
                Object h = t.getValueAt(row, 2);
                if (h != null && Integer.parseInt(h.toString().replace("%", "")) < 50)
                    alert = true;
            } catch (Exception ignored) {
            }
            try {
                Object r = t.getValueAt(row, 1);
                if (r != null && r.toString().endsWith(" ms")
                        && Integer.parseInt(r.toString().replace(" ms", "")) >= 200)
                    alert = true;
            } catch (Exception ignored) {
            }
            l.setBackground(sel ? ACCENT.darker() : alert ? ALERT_BG : PANEL_BG);
            l.setForeground(alert ? RED : FG);
            return l;
        }
    }

    static class RttBarR extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int row, int col) {
            String txt = v == null ? "n/a" : v.toString();
            int ms = -1;
            try {
                if (txt.endsWith(" ms"))
                    ms = Integer.parseInt(txt.replace(" ms", ""));
            } catch (NumberFormatException ig) {
            }
            if (ms < 0) {
                JLabel l = new JLabel("  n/a");
                l.setForeground(MUTED);
                l.setBackground(PANEL_BG);
                l.setOpaque(true);
                l.setFont(MONO);
                return l;
            }
            JPanel cell = new JPanel(new BorderLayout(4, 0));
            cell.setBackground(sel ? ACCENT.darker() : PANEL_BG);
            cell.setBorder(new EmptyBorder(2, 6, 2, 6));
            JProgressBar bar = new JProgressBar(0, 300);
            bar.setValue(Math.min(ms, 300));
            bar.setStringPainted(false);
            bar.setBorderPainted(false);
            bar.setPreferredSize(new Dimension(60, 10));
            Color c = ms < 20 ? GREEN : ms < 100 ? YELLOW : RED;
            bar.setForeground(c);
            bar.setBackground(BG);
            JLabel lbl = new JLabel(txt);
            lbl.setFont(MONO);
            lbl.setForeground(c);
            cell.add(lbl, BorderLayout.WEST);
            cell.add(bar, BorderLayout.CENTER);
            return cell;
        }
    }

    static class HealthR extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int row, int col) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, f, row, col);
            l.setOpaque(true);
            l.setBackground(sel ? ACCENT.darker() : PANEL_BG);
            try {
                int h = Integer.parseInt(l.getText().replace("%", ""));
                l.setForeground(h >= 80 ? GREEN : h >= 50 ? YELLOW : RED);
            } catch (NumberFormatException ig) {
                l.setForeground(MUTED);
            }
            return l;
        }
    }

    static class BadgeR extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int row, int col) {
            String txt = v == null ? "" : v.toString();
            boolean drain = "DRAINED".equalsIgnoreCase(txt);
            JLabel l = new JLabel("  " + txt + "  ", SwingConstants.CENTER);
            l.setOpaque(true);
            l.setFont(SMALL);
            l.setForeground(Color.WHITE);
            l.setBackground(drain ? RED : GREEN.darker());
            return l;
        }
    }

    static class AssignR extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object val, int i, boolean sel, boolean f) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, val, i, sel, f);
            l.setFont(MONO);
            l.setBorder(new EmptyBorder(2, 8, 2, 8));
            String t = val == null ? "" : val.toString();
            if (t.startsWith("[DYN]")) {
                l.setForeground(ACCENT);
                l.setText("⬤ " + t.substring(5).trim());
            } else if (t.startsWith("[STK]")) {
                l.setForeground(GREEN);
                l.setText("⬡ " + t.substring(5).trim());
            } else if (t.startsWith("[STA]")) {
                l.setForeground(YELLOW);
                l.setText("⬤ " + t.substring(5).trim());
            }
            l.setBackground(sel ? CARD_BG.brighter() : PANEL_BG);
            return l;
        }
    }

    static class ProcR extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int row, int col) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, f, row, col);
            l.setOpaque(true);
            String txt = v == null ? "" : v.toString();
            if ("RUNNING".equals(txt)) {
                l.setForeground(GREEN);
                l.setBackground(sel ? ACCENT.darker() : PANEL_BG);
            } else if ("STOPPED".equals(txt)) {
                l.setForeground(MUTED);
                l.setBackground(sel ? ACCENT.darker() : new Color(0x14, 0x14, 0x28));
            } else if ("SERVER".equals(txt) || ManagedProcess.PType.SERVER.toString().equals(txt)) {
                l.setForeground(ACCENT);
                l.setBackground(sel ? ACCENT.darker() : PANEL_BG);
            } else {
                l.setForeground(YELLOW);
                l.setBackground(sel ? ACCENT.darker() : PANEL_BG);
            }
            return l;
        }
    }

    // ─── UI helpers
    // ───────────────────────────────────────────────────────────────
    private static DefaultTableModel noEdit(String... cols) {
        return new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
    }

    private static void applyDarkDefaults() {
        UIManager.put("Table.background", PANEL_BG);
        UIManager.put("Table.foreground", FG);
        UIManager.put("Table.gridColor", CARD_BG);
        UIManager.put("Table.selectionBackground", ACCENT.darker());
        UIManager.put("Table.selectionForeground", Color.WHITE);
        UIManager.put("TableHeader.background", CARD_BG);
        UIManager.put("TableHeader.foreground", ACCENT);
        UIManager.put("ScrollPane.background", PANEL_BG);
        UIManager.put("Viewport.background", PANEL_BG);
        UIManager.put("SplitPane.background", BG);
        UIManager.put("List.background", PANEL_BG);
        UIManager.put("List.foreground", FG);
        UIManager.put("List.selectionBackground", CARD_BG.brighter());
        UIManager.put("Label.foreground", FG);
        UIManager.put("Panel.background", BG);
        UIManager.put("TabbedPane.background", CARD_BG);
        UIManager.put("TabbedPane.foreground", MUTED);
    }

    private static void styleTable(JTable t) {
        t.setFont(MONO);
        t.setRowHeight(26);
        t.setShowGrid(true);
        t.setGridColor(CARD_BG);
        t.setBackground(PANEL_BG);
        t.setForeground(FG);
        t.setSelectionBackground(ACCENT.darker());
        t.setSelectionForeground(Color.WHITE);
        t.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        JTableHeader h = t.getTableHeader();
        h.setFont(BOLD);
        h.setBackground(CARD_BG);
        h.setForeground(ACCENT);
        h.setReorderingAllowed(false);
        h.setBorder(new MatteBorder(0, 0, 1, 0, ACCENT.darker()));
    }

    private static <T extends JComponent> T dark(T c) {
        c.setBackground(BG);
        return c;
    }

    private static JLabel pill(String t, Color fg) {
        JLabel l = new JLabel(t);
        l.setFont(BOLD);
        l.setForeground(fg);
        return l;
    }

    private static JLabel info(String t) {
        JLabel l = new JLabel(t);
        l.setFont(SMALL);
        l.setForeground(MUTED);
        return l;
    }

    private static JLabel sep() {
        JLabel l = new JLabel("|");
        l.setForeground(new Color(0x33, 0x33, 0x55));
        return l;
    }

    private static JScrollPane scroll(Component inner, String title) {
        JScrollPane sp = new JScrollPane(inner);
        sp.setBackground(PANEL_BG);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(CARD_BG, 1, true),
                " " + title + " ", TitledBorder.LEFT, TitledBorder.TOP, BOLD, ACCENT));
        sp.getViewport().setBackground(PANEL_BG);
        return sp;
    }

    private static JScrollPane scrollRaw(Component inner, String title) {
        JScrollPane sp = new JScrollPane(inner);
        sp.setBackground(PANEL_BG);
        sp.getViewport().setBackground(PANEL_BG);
        sp.setBorder(null);
        return sp;
    }

    private static JButton mkBtn(String txt, Color bg) {
        JButton b = new JButton(txt);
        b.setFont(BOLD);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    static String formatDur(long s) {
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        return h > 0 ? h + "h " + String.format("%02dm", m) : m > 0 ? m + "m " + String.format("%02ds", ss) : s + "s";
    }

    // ─── Entry point ─────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int sp = args.length > 1 ? Integer.parseInt(args[1]) : 11116;
        int cp = args.length > 2 ? Integer.parseInt(args[2]) : 11117;
        if (args.length > 3)
            projectDir = new File(args[3]);
        SwingUtilities.invokeLater(() -> new VisualOversee(host, sp, cp));
    }
}
