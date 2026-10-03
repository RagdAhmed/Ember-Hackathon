import java.awt.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Pop-up wrapper (opened from the menu) around the journey picker. */
final class JourneyDialog extends JDialog {
    JourneyDialog(Frame owner, Consumer<Journey> onPick) {
        super(owner, "Choose your journey", true);
        setContentPane(new JourneyPicker(new EmberApi(), j -> { onPick.accept(j); dispose(); }));
        setSize(1060, 580);
        setLocationRelativeTo(owner);
    }
}

/** From -> To -> departure, all loaded live from the Ember API. */
final class JourneyPicker extends SkyPanel {
    private final EmberApi api;
    private final Consumer<Journey> onPick;
    private final AtomicInteger fromSeq = new AtomicInteger(), toSeq = new AtomicInteger(), depSeq = new AtomicInteger();
    final DefaultListModel<Place> fromModel = new DefaultListModel<>(), toModel = new DefaultListModel<>();
    final DefaultListModel<Departure> depModel = new DefaultListModel<>();
    final JList<Place> fromList = styled(new JList<>(fromModel)), toList = styled(new JList<>(toModel));
    final JList<Departure> depList = styled(new JList<>(depModel));
    private final JTextField fromField = field("Search departure stop…"), toField = field("Search destination…");
    private final JLabel status = new JLabel(" ");
    private final PillButton use = new PillButton("USE THIS JOURNEY", Ui.TEAL, 300);
    private Place from, to;

    JourneyPicker(EmberApi api, Consumer<Journey> onPick) {
        this.api = api;
        this.onPick = onPick;
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(22, 28, 20, 28));

        JLabel title = new JLabel("Choose your journey");
        title.setFont(Ui.display(Font.BOLD, 32));
        title.setForeground(Ui.INK);
        JLabel sub = new JLabel("Live stops and times from Ember. Pick where you're travelling from, to, and which coach.");
        sub.setFont(Ui.font(Font.PLAIN, 14));
        sub.setForeground(Ui.MUTED);
        JPanel head = new JPanel(new GridLayout(2, 1, 0, 2));
        head.setOpaque(false);
        head.add(title);
        head.add(sub);
        add(head, BorderLayout.NORTH);

        JPanel cols = new JPanel(new GridLayout(1, 3, 16, 0));
        cols.setOpaque(false);
        cols.add(column("1  From", fromField, fromList));
        cols.add(column("2  To", toField, toList));
        cols.add(column("3  Departure", null, depList));
        add(cols, BorderLayout.CENTER);

        status.setFont(Ui.font(Font.BOLD, 13));
        status.setForeground(Ui.MUTED);
        use.setEnabled(false);
        use.addActionListener(e -> confirm());
        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.add(status, BorderLayout.CENTER);
        foot.add(use, BorderLayout.EAST);
        add(foot, BorderLayout.SOUTH);

        debounce(fromField, this::loadFrom);
        debounce(toField, this::loadTo);
        fromList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || fromList.getSelectedValue() == null) return;
            from = fromList.getSelectedValue();
            to = null;
            toModel.clear();
            depModel.clear();
            use.setEnabled(false);
            loadTo();
        });
        toList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || toList.getSelectedValue() == null) return;
            to = toList.getSelectedValue();
            depModel.clear();
            use.setEnabled(false);
            loadDepartures();
        });
        depList.addListSelectionListener(e -> use.setEnabled(depList.getSelectedValue() != null));
        loadFrom();
    }

    private void loadFrom() {
        async(fromSeq, () -> api.search(fromField.getText(), null), places -> {
            fromModel.clear();
            places.forEach(fromModel::addElement);
            if (places.isEmpty()) status("No stops match that search.");
        });
    }

    private void loadTo() {
        if (from == null) return;
        int origin = from.id();
        async(toSeq, () -> api.search(toField.getText(), origin), places -> {
            toModel.clear();
            places.forEach(toModel::addElement);
            if (places.isEmpty()) status("No destinations found from " + from.shortName() + ".");
        });
    }

    private void loadDepartures() {
        Place f = from, t = to;
        async(depSeq, () -> api.departures(f, t), deps -> {
            depModel.clear();
            deps.forEach(depModel::addElement);
            if (deps.isEmpty()) status("No coaches in the next 48 hours for that route.");
            else depList.setSelectedIndex(0);
        });
    }

    private void confirm() {
        Departure d = depList.getSelectedValue();
        if (from == null || to == null || d == null) return;
        // The quotes API gives times but not distance, so estimate road distance from the stop coordinates.
        double km = from.hasCoords() && to.hasCoords()
            ? EmberApi.haversineKm(from.lat(), from.lon(), to.lat(), to.lon()) * Config.ROAD_FACTOR
            : d.minutes() * 0.9;
        onPick.accept(new Journey(from.shortName(), to.shortName(), Math.max(1, Math.round(km)), d.minutes()));
    }

    /* ---- helpers ---- */

    private <T> void async(AtomicInteger seq, Callable<T> job, Consumer<T> ok) {
        int mine = seq.incrementAndGet();               // newer requests make older ones obsolete
        status("Loading…");
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception { return job.call(); }
            protected void done() {
                if (mine != seq.get()) return;
                try { status(" "); ok.accept(get()); }
                catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    String m = c.getMessage();
                    status(m != null && m.startsWith("HTTP") ? "Ember returned an error (" + m + ") - try again shortly."
                        : "Couldn't reach Ember - check your internet connection.");
                }
            }
        }.execute();
    }

    private void status(String s) { status.setText(s); }

    static void debounce(JTextField f, Runnable action) {
        Timer t = new Timer(350, e -> action.run());
        t.setRepeats(false);
        f.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { t.restart(); }
            public void removeUpdate(DocumentEvent e) { t.restart(); }
            public void changedUpdate(DocumentEvent e) { t.restart(); }
        });
    }

    static JTextField field(String hint) {
        JTextField f = new JTextField() {                      // Swing has no placeholder, so draw the hint ourselves
            protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                if (!getText().isEmpty()) return;
                Graphics2D g = (Graphics2D) g0.create();
                Ui.aa(g);
                g.setColor(new Color(0x8A9A93));
                g.setFont(getFont());
                Insets in = getInsets();
                g.drawString(hint, in.left, (getHeight() + g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2);
                g.dispose();
            }
        };
        f.setFont(Ui.font(Font.PLAIN, 15));
        f.setForeground(Ui.INK);
        f.setToolTipText(hint);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.MIST, 2, true),
            BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        return f;
    }

    static <T> JList<T> styled(JList<T> l) {
        l.setFont(Ui.font(Font.PLAIN, 15));
        l.setForeground(Ui.INK);
        l.setSelectionBackground(Ui.SOFT);
        l.setSelectionForeground(Ui.INK);
        l.setFixedCellHeight(36);
        l.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        l.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        return l;
    }

    static JPanel column(String heading, JTextField search, JList<?> list) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setOpaque(false);
        JLabel h = new JLabel(heading);
        h.setFont(Ui.font(Font.BOLD, 16));
        h.setForeground(Ui.TEAL_DARK);
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(h, BorderLayout.NORTH);
        if (search != null) top.add(search, BorderLayout.CENTER);
        else top.add(Box.createRigidArea(new Dimension(0, 36)), BorderLayout.CENTER);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createLineBorder(Ui.MIST, 2, true));
        sp.getViewport().setBackground(Color.WHITE);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        p.add(top, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }
}