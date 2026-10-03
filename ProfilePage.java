import java.awt.*;
import java.awt.geom.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;

/**
 * Profile card + forest. Used for your own profile (edit bio) and for a friend's (view their forest,
 * add / remove them). Call show(username, backTarget) before switching to this page.
 */
final class ProfilePage extends SkyPanel {
    private static final Color ERR = new Color(0xB4443C);

    private final Accounts accounts;
    private final Consumer<String> nav;
    private final Supplier<String> me;
    private final Garden garden = new Garden();                    // whichever user is on screen (not the live game garden)
    private final ForestCanvas canvas = new ForestCanvas(garden);
    private final AtomicInteger seq = new AtomicInteger();

    private final JLabel title = new JLabel("MY PROFILE", SwingConstants.CENTER);
    private final Avatar avatar = new Avatar();
    private final JLabel name = Ui.label(" ", 28, Font.BOLD, Ui.INK);
    private final JLabel joined = Ui.label(" ", 13, Font.PLAIN, Ui.MUTED);
    private final JTextArea bio = new JTextArea();                 // plain text on purpose: a bio is written by another user
    private final JLabel kg = Ui.label(" ", 24, Font.BOLD, Ui.TEAL_DARK);
    private final JLabel trips = Ui.label(" ", 24, Font.BOLD, Ui.TEAL_DARK);
    private final JLabel pals = Ui.label(" ", 24, Font.BOLD, Ui.TEAL_DARK);
    private final JPanel badges = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
    private final JLabel badgeCount = Ui.label(" ", 13, Font.BOLD, Ui.TEAL_DARK);
    private final JLabel status = Ui.label(" ", 13, Font.BOLD, Ui.MUTED);
    private final PillButton action = new PillButton("EDIT BIO", Ui.TEAL, 280);
    private final JButton backLink = new JButton("←  Back to friends");

    private String viewing, back = "timer", shownBio = "";
    private boolean self, friend;

    ProfilePage(Accounts accounts, Consumer<String> nav, Supplier<String> me) {
        super(true);
        this.accounts = accounts;
        this.nav = nav;
        this.me = me;
        setLayout(new BorderLayout());
        canvas.setOnMapChange((m, current) -> setTheme(m.skyTop(), m.skyBottom()));

        // top bar: menu button + title
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(16, 20, 4, 20));
        bar.add(new MenuButton(() -> nav.accept("menu")), BorderLayout.WEST);
        title.setFont(Ui.display(Font.BOLD, 38));
        title.setForeground(Ui.INK);
        bar.add(title, BorderLayout.CENTER);
        bar.add(Box.createRigidArea(new Dimension(56, 44)), BorderLayout.EAST);
        add(bar, BorderLayout.NORTH);

        // left: profile card
        name.setFont(Ui.display(Font.BOLD, 28));
        bio.setEditable(false);
        bio.setFocusable(false);
        bio.setOpaque(false);
        bio.setLineWrap(true);
        bio.setWrapStyleWord(true);
        bio.setFont(Ui.font(Font.PLAIN, 14));
        bio.setForeground(Ui.INK);
        bio.setBorder(null);
        Ui.fixed(bio, 280, 78);
        badges.setOpaque(false);
        Ui.fixed(badges, 280, 34);
        Ui.fixed(status, 280, 20);

        JPanel stats = new JPanel(new GridLayout(1, 3, 6, 0));
        stats.setOpaque(false);
        stats.add(statBox(kg, "kg CO₂ saved"));
        stats.add(statBox(trips, "journeys"));
        stats.add(statBox(pals, "friends"));
        Ui.fixed(stats, 290, 60);

        action.addActionListener(e -> { if (viewing != null) { if (self) editBio(); else toggleFriend(); } });

        JPanel card = new JPanel() {
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Ui.aa(g);
                g.setColor(new Color(255, 255, 255, 235));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 30, 30);
                g.setColor(Ui.MIST);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 30, 30);
                g.dispose();
            }
        };
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(22, 16, 18, 16));
        backLink.setFont(Ui.font(Font.BOLD, 13));
        backLink.setForeground(Ui.TEAL_DARK);
        Ui.plainButton(backLink, 200, 26);
        backLink.setAlignmentX(0.5f);
        backLink.setMaximumSize(new Dimension(200, 26));
        backLink.addActionListener(e -> nav.accept(back));
        card.add(backLink);
        card.add(Ui.gap(4));
        card.add(avatar);
        card.add(Ui.gap(10));
        card.add(name);
        card.add(joined);
        card.add(Ui.gap(8));
        card.add(bio);
        card.add(Ui.gap(8));
        card.add(stats);
        card.add(Ui.gap(14));
        card.add(badgeCount);
        card.add(Ui.gap(4));
        card.add(badges);
        card.add(Box.createVerticalGlue());
        card.add(status);
        card.add(Ui.gap(4));
        card.add(action);
        card.setPreferredSize(new Dimension(320, 400));

        JPanel body = new JPanel(new BorderLayout(16, 0));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(6, 24, 20, 12));
        body.add(card, BorderLayout.WEST);
        body.add(canvas, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
    }

    /** Load and show a user. backTarget is the page the arrow returns to ("timer" or "friends"). */
    void show(String username, String backTarget) {
        viewing = username;
        back = backTarget;
        action.setEnabled(false);
        setStatus("Loading…", false);
        name.setText("…");
        String viewer = me.get();
        int mine = seq.incrementAndGet();                         // newer requests make older ones obsolete
        new SwingWorker<Accounts.PublicProfile, Void>() {
            protected Accounts.PublicProfile doInBackground() throws Exception { return accounts.view(viewer, username); }
            protected void done() {
                if (mine != seq.get()) return;
                try { apply(get()); }
                catch (Exception e) {
                    name.setText("Profile unavailable");
                    setStatus(message(e), true);
                }
            }
        }.execute();
    }

    private void apply(Accounts.PublicProfile p) {
        self = p.self();
        friend = p.friend();
        garden.load(p.bankedKg(), p.mapIndex());
        canvas.setBannerText(self ? "🎉  You saved the forest!" : "🎉  " + p.username() + " saved the forest!");
        canvas.showCurrent();                                  // re-tints the page and starts on their current forest

        title.setText(self ? "MY PROFILE" : "PROFILE");
        backLink.setVisible(!self && back.equals("friends"));
        avatar.setInitialOf(p.username());
        name.setText(p.username());
        joined.setText(p.joined().isEmpty() ? " " : "Joined " + p.joined());
        shownBio = p.bio();
        bio.setText(!p.bio().isEmpty() ? p.bio() : self ? "Add a short bio so your friends know who you are." : "No bio yet.");
        kg.setText(String.format("%.1f", p.bankedKg()));
        trips.setText(String.valueOf(p.journeys()));
        pals.setText(String.valueOf(p.friendCount()));

        badges.removeAll();
        for (Badge b : garden.unlocked) {
            JLabel l = new JLabel(b.icon());
            l.setFont(Ui.font(Font.PLAIN, 22));
            l.setToolTipText(b.name() + " - " + b.desc());
            badges.add(l);
        }
        badgeCount.setText("Badges  " + garden.unlocked.size() + " / " + Config.BADGES.size());
        badges.revalidate();
        badges.repaint();

        action.setText(self ? "EDIT BIO" : friend ? "REMOVE FRIEND" : "ADD FRIEND");
        action.setColor(!self && friend ? Ui.INK : Ui.TEAL);
        action.setEnabled(true);
        setStatus(" ", false);
        revalidate();
        repaint();
    }

    /* ---- actions (file access stays off the UI thread) ---- */

    private interface Task { void run() throws Accounts.AuthException; }

    private void editBio() {
        Object in = JOptionPane.showInputDialog(this, "Write a short bio (up to " + Accounts.MAX_BIO + " characters):",
            "Edit bio", JOptionPane.PLAIN_MESSAGE, null, null, shownBio);
        if (in == null) return;
        String who = viewing, text = in.toString();
        run(() -> accounts.setBio(who, text));
    }

    private void toggleFriend() {
        String user = me.get(), other = viewing;
        boolean remove = friend;
        run(() -> { if (remove) accounts.removeFriend(user, other); else accounts.addFriend(user, other); });
    }

    private void run(Task t) {
        action.setEnabled(false);
        String who = viewing;
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception { t.run(); return null; }
            protected void done() {
                try { get(); show(who, back); }
                catch (Exception e) { action.setEnabled(true); setStatus(message(e), true); }
            }
        }.execute();
    }

    private static String message(Exception e) {
        Throwable c = e.getCause() != null ? e.getCause() : e;
        return c instanceof Accounts.AuthException ? c.getMessage() : "Something went wrong - try again.";
    }

    private void setStatus(String s, boolean error) {
        status.setForeground(error ? ERR : Ui.MUTED);
        status.setText(s);
    }

    private static JPanel statBox(JLabel value, String caption) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(value);
        p.add(Ui.label(caption, 12, Font.PLAIN, Ui.MUTED));
        return p;
    }

    /** Round badge with the user's initial, in the same style as the tree circle on the timer page. */
    private static final class Avatar extends JComponent {
        private String initial = "?";
        Avatar() { Ui.fixed(this, 104, 104); }
        void setInitialOf(String n) { initial = n == null || n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase(); repaint(); }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            Ellipse2D c = new Ellipse2D.Double(4, 4, 96, 96);
            g.setPaint(new GradientPaint(0, 4, Ui.SAGE, 0, 100, Ui.TEAL));
            g.fill(c);
            g.setStroke(new BasicStroke(4f));
            g.setColor(Ui.INK);
            g.draw(new Ellipse2D.Double(6, 6, 92, 92));
            g.setFont(Ui.display(Font.BOLD, 46));
            g.setColor(Color.WHITE);
            FontMetrics fm = g.getFontMetrics();
            Ui.drawCentered(g, initial, 52, 52 + (fm.getAscent() - fm.getDescent()) / 2.0);
            g.dispose();
        }
    }
}