import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.Timer;

/**
 * Coach CO2 Savings Tracker - main/shared code.
 * Run with:
 *   javac *.java
 *   java CoachCo2App
 */
public class CoachCo2App {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new AppFrame().setVisible(true));
    }
}

/* ===================== CONFIG ===================== */
final class Config {
    static final double SAVED_KG_PER_KM = 0.248;   // coach vs car, kg CO2 saved per km
    static final boolean REAL_TIME = false;        // false: replay a journey in DEMO_SECONDS; true: run it in real time
    static final int DEMO_SECONDS = 30;            // real seconds the replayed journey takes (when REAL_TIME is false)
    static final double ROAD_FACTOR = 1.15;        // road distance ~ straight-line distance x this (API gives no distance)
    static final double FULL_TREE_KG = 18.6;       // kg that makes one fully grown tree
    static final int FOREST_GRID = 4;              // forest plot is GRID x GRID tiles (max trees = GRID^2)
    static final double[] STAGE_KG = {0, 1, 5, 10, 16};
    static final String[] STAGE_NAME = {"Seed", "Sprout", "Young tree", "Mature tree", "Large tree"};
    static final List<Badge> BADGES = List.of(
        new Badge("🌱", "First Sprout", "First CO₂ saved", 0.01),
        new Badge("🌿", "Growing Green", "5 kg CO₂ saved", 5),
        new Badge("🌳", "Tree Planter", "10 kg CO₂ saved", 10),
        new Badge("🌲", "Forest Builder", "25 kg CO₂ saved", 25),
        new Badge("🌍", "Planet Protector", "50 kg CO₂ saved", 50),
        new Badge("🏆", "Forest Guardian", "100 kg CO₂ saved", 100));

    static int journeySeconds(Journey j) { return REAL_TIME ? j.durationMin() * 60 : DEMO_SECONDS; }

    static double savedKg(double km) { return km * SAVED_KG_PER_KM; }

    static String stage(double kg) {
        for (int i = STAGE_KG.length - 1; i >= 0; i--) if (kg >= STAGE_KG[i]) return STAGE_NAME[i];
        return STAGE_NAME[0];
    }
}

/* ===================== DATA / TRACKING LAYER ===================== */
record Journey(String from, String to, double distanceKm, int durationMin) {}
record Badge(String icon, String name, String desc, double kg) {}
record Snapshot(double progress, double distanceKm, double totalKm, double co2Kg, int elapsedSec) {
    double remainingKm() { return Math.max(0, totalKm - distanceKm); }
}

/** Replace MockJourneyTracker with a real API-backed implementation later. */
interface JourneyTracker {
    void start(Journey journey, Consumer<Snapshot> listener);
    Snapshot stop();
}

final class MockJourneyTracker implements JourneyTracker {
    private Timer timer;
    private Journey journey;
    private long startMs;
    private Snapshot last;

    public void start(Journey j, Consumer<Snapshot> listener) {
        stop();
        journey = j;
        startMs = System.currentTimeMillis();
        last = snap(0);
        listener.accept(last);
        timer = new Timer(100, e -> {
            double p = Math.min(1, (System.currentTimeMillis() - startMs) / (Config.journeySeconds(journey) * 1000.0));
            last = snap(p);
            listener.accept(last);
            if (p >= 1) timer.stop();
        });
        timer.start();
    }

    public Snapshot stop() {
        if (timer != null) timer.stop();
        return last;
    }

    private Snapshot snap(double p) {
        double d = journey.distanceKm() * p;
        return new Snapshot(p, d, journey.distanceKm(), Config.savedKg(d),
            (int) Math.round(journey.durationMin() * 60 * p));
    }
}

/** Everything the user has saved so far (lives across journeys while the app is open). */
final class Garden {
    double bankedKg, liveKg;
    final Set<Badge> unlocked = new LinkedHashSet<>();

    double total() { return bankedKg + liveKg; }

    void bank() { bankedKg += liveKg; liveKg = 0; }

    List<Badge> checkUnlocks() {
        List<Badge> fresh = new ArrayList<>();
        for (Badge b : Config.BADGES) if (total() >= b.kg() && unlocked.add(b)) fresh.add(b);
        return fresh;
    }
}

/* ===================== UI HELPERS ===================== */
final class Ui {
    /** Palette (from the supplied swatch). */
    static final Color TEAL = new Color(0x4F917A),    // primary
        PALE = new Color(0xD9E7E2),                   // lightest tint
        SAGE = new Color(0x8AB5A6),
        MIST = new Color(0xC7DBD4),
        SOFT = new Color(0xA5C7BB);
    /** Supporting colours derived from the palette / sketches. */
    static final Color INK = new Color(0x4B5C56),     // dark slate used for text + plates in the sketches
        TEAL_DARK = new Color(0x2F6B57),
        TEAL_MID = new Color(0x3E8069),
        MUTED = new Color(0x56695F),
        TRUNK = new Color(0x6F5646),                  // soft earth brown for trunks
        SOIL_L = new Color(0x7A5C4B), SOIL_R = new Color(0x5F4637);

    private static final Set<String> FAMILIES = new HashSet<>(Arrays.asList(
        GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
    /** Heavy serif for headings / the timer (closest to the sketch lettering that ships with most systems). */
    static final String DISPLAY = pick("Cambria", "Times New Roman", "Serif");

    private static String pick(String... names) {
        for (String n : names) if (FAMILIES.contains(n)) return n;
        return names[names.length - 1];
    }

    static Font font(int style, float size) { return new Font("SansSerif", style, (int) size).deriveFont(style, size); }

    static Font display(int style, float size) { return new Font(DISPLAY, style, 12).deriveFont(style, size); }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    static JLabel label(String t, float size, int style, Color c) {
        JLabel l = new JLabel(t, SwingConstants.CENTER);
        l.setFont(font(style, size));
        l.setForeground(c);
        l.setAlignmentX(0.5f);
        return l;
    }

    static <T extends JComponent> T fixed(T c, int w, int h) {
        c.setPreferredSize(new Dimension(w, h));
        c.setMaximumSize(new Dimension(w, h));
        c.setAlignmentX(0.5f);
        return c;
    }

    static Component gap(int h) { return Box.createRigidArea(new Dimension(0, h)); }

    static void drawCentered(Graphics2D g, String s, double cx, double baseline) {
        g.drawString(s, (float) (cx - g.getFontMetrics().stringWidth(s) / 2.0), (float) baseline);
    }

    static void plainButton(AbstractButton b, int w, int h) {
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(w, h));
    }

    /** Top bar used by the Forest and Badges pages:  <-  TITLE  (title optional). */
    static JPanel topBar(String title, Consumer<String> nav) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(16, 20, 4, 20));
        bar.add(new BackButton(() -> nav.accept("timer")), BorderLayout.WEST);
        if (title != null) {
            JLabel l = new JLabel(title, SwingConstants.CENTER);
            l.setFont(display(Font.BOLD, 38));
            l.setForeground(INK);
            bar.add(l, BorderLayout.CENTER);
        }
        bar.add(Box.createRigidArea(new Dimension(56, 44)), BorderLayout.EAST);
        return bar;
    }
}

/** Page background: white fading to a pale palette tint (or a fuller tint for the forest). */
class SkyPanel extends JPanel {
    private final Color top, bottom;
    SkyPanel() { this(false); }
    SkyPanel(boolean tinted) {
        top = tinted ? Ui.PALE : Color.WHITE;
        bottom = tinted ? new Color(0xEEF5F2) : new Color(0xE9F1EE);
    }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bottom));
        g.fillRect(0, 0, getWidth(), getHeight());
    }
}

/** Bold arrow, top-left of the Forest and Badges pages. */
class BackButton extends JButton {
    BackButton(Runnable action) {
        Ui.plainButton(this, 56, 44);
        setToolTipText("Back");
        addActionListener(e -> action.run());
    }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.setColor(getModel().isRollover() ? Ui.TEAL : Ui.INK);
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(15, 22, 43, 22));
        g.draw(new Line2D.Double(15, 22, 26, 11));
        g.draw(new Line2D.Double(15, 22, 26, 33));
        g.dispose();
    }
}

/** Hamburger menu (top-left of the timer page). */
class MenuButton extends JButton {
    MenuButton(Consumer<String> nav) {
        Ui.plainButton(this, 56, 44);
        setToolTipText("Menu");
        JPopupMenu pm = new JPopupMenu();
        pm.setBackground(Color.WHITE);
        pm.setBorder(BorderFactory.createLineBorder(Ui.MIST, 1));
        for (String[] item : new String[][]{{"🚌  Choose journey", "journey"}, {"🌲  Forest", "forest"}, {"🏅  Badges", "badges"}}) {
            JMenuItem mi = new JMenuItem(item[0]);
            mi.setFont(Ui.font(Font.PLAIN, 16));
            mi.setForeground(Ui.INK);
            mi.setBackground(Color.WHITE);
            mi.setBorder(BorderFactory.createEmptyBorder(9, 16, 9, 28));
            mi.addActionListener(e -> nav.accept(item[1]));
            pm.add(mi);
        }
        addActionListener(e -> pm.show(this, 0, getHeight()));
    }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.setColor(getModel().isRollover() ? Ui.TEAL : Ui.INK);
        for (int i = 0; i < 3; i++) g.fillRoundRect(12, 11 + i * 9, 32, 4, 4, 4);
        g.dispose();
    }
}

/** Rounded call-to-action button ("START JOURNEY"). */
class PillButton extends JButton {
    private Color c;
    PillButton(String text, Color c, int w) {
        super(text);
        this.c = c;
        setFont(Ui.font(Font.BOLD, 18));
        setForeground(Color.WHITE);
        Ui.plainButton(this, w, 62);
        setMaximumSize(new Dimension(w, 62));
        setAlignmentX(0.5f);
    }
    void setColor(Color c) { this.c = c; repaint(); }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        int w = getWidth(), h = getHeight() - 6;
        g.setColor(new Color(0, 0, 0, 28));
        g.fillRoundRect(2, 5, w - 4, h, h, h);
        Color fill = !isEnabled() ? mix(c, Color.WHITE, 0.55) : getModel().isPressed() ? c.darker()
            : getModel().isRollover() ? mix(c, Color.WHITE, 0.12) : c;
        g.setColor(fill);
        g.fillRoundRect(0, 0, w, h, h, h);
        g.setFont(getFont());
        g.setColor(getForeground());
        FontMetrics fm = g.getFontMetrics();
        // letter-spaced caps
        String s = getText();
        float track = 1.5f, tw = fm.stringWidth(s) + track * (s.length() - 1), x = (w - tw) / 2f;
        float y = (h + fm.getAscent() - fm.getDescent()) / 2f;
        for (char ch : s.toCharArray()) { g.drawString(String.valueOf(ch), x, y); x += fm.charWidth(ch) + track; }
        g.dispose();
    }
    private static Color mix(Color a, Color b, double t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
            (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}

/** Big 00:00 readout - digits sit in fixed-width cells so the timer never jitters. */
class BigTime extends JComponent {
    private String text = "00:00";
    BigTime() { Ui.fixed(this, 460, 108); }
    void setText(String s) { if (!s.equals(text)) { text = s; repaint(); } }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.setFont(Ui.display(Font.BOLD, 92));
        g.setColor(Ui.INK);
        FontMetrics fm = g.getFontMetrics();
        int cell = 0;
        for (char ch = '0'; ch <= '9'; ch++) cell = Math.max(cell, fm.charWidth(ch));
        int colon = fm.charWidth(':'), total = 0;
        for (char ch : text.toCharArray()) total += ch == ':' ? colon : cell;
        float x = (getWidth() - total) / 2f, y = getHeight() / 2f + (fm.getAscent() - fm.getDescent()) / 2f;
        for (char ch : text.toCharArray()) {
            int adv = ch == ':' ? colon : cell;
            g.drawString(String.valueOf(ch), x + (adv - fm.charWidth(ch)) / 2f, y);
            x += adv;
        }
        g.dispose();
    }
}

class Bar extends JComponent {
    private double v;
    Bar() { Ui.fixed(this, 340, 10); }
    void set(double p) { v = p; repaint(); }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.setColor(Ui.MIST);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        int fw = (int) (getWidth() * v);
        if (fw > 0) {
            g.setPaint(new GradientPaint(0, 0, Ui.SAGE, getWidth(), 0, Ui.TEAL));
            g.fillRoundRect(0, 0, Math.max(fw, getHeight()), getHeight(), getHeight(), getHeight());
        }
        g.dispose();
    }
}

/** Small toast that queues messages and fades them out. */
class Toast extends JComponent {
    private final Deque<String> queue = new ArrayDeque<>();
    private String text = "";
    private float alpha;
    private long until;
    Toast() {
        Ui.fixed(this, 420, 38);
        new Timer(30, e -> {
            long now = System.currentTimeMillis();
            if (now >= until && !queue.isEmpty()) { text = queue.poll(); alpha = 1; until = now + 2400; }
            else if (now > until && alpha > 0) alpha = Math.max(0, alpha - 0.06f);
            repaint();
        }).start();
    }
    void show(String msg) { queue.add(msg); }
    void clear() { queue.clear(); alpha = 0; until = 0; }
    protected void paintComponent(Graphics g0) {
        if (alpha <= 0) return;
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.setComposite(AlphaComposite.SrcOver.derive(alpha));
        g.setColor(Ui.INK);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        g.setColor(Color.WHITE);
        g.setFont(Ui.font(Font.BOLD, 14));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
    }
}

/* ===================== BADGE TILE (circle + name plate) ===================== */
class BadgeTile extends JComponent {
    private final Badge b;
    private boolean on;
    private double pop;
    BadgeTile(Badge b) {
        this.b = b;
        setToolTipText(b.name() + " - " + b.desc());
        new Timer(30, e -> { if (pop > 0) { pop = Math.max(0, pop - 0.04); repaint(); } }).start();
    }
    void setUnlocked(boolean u) {
        if (u && !on) pop = 1;
        if (u != on) { on = u; repaint(); }
    }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        int w = getWidth(), h = getHeight(), plateH = 64, gap = 18;
        int d = Math.max(40, Math.min(Math.min((int) (w * 0.62), 190), h - plateH - gap - 10));
        double cx = w / 2.0, oy = Math.max(0, (h - (d + gap + plateH)) / 2.0), cy = oy + d / 2.0;

        // circle (pops when newly unlocked)
        AffineTransform keep = g.getTransform();
        double s = 1 + 0.15 * Math.sin(pop * Math.PI);
        g.translate(cx, cy);
        g.scale(s, s);
        g.translate(-cx, -cy);
        if (on) {
            g.setColor(new Color(75, 92, 86, 22));
            g.fill(new Ellipse2D.Double(cx - d / 2.0 + 3, oy + 7, d, d));
        }
        g.setColor(on ? Ui.SOFT : Ui.PALE);
        g.fill(new Ellipse2D.Double(cx - d / 2.0, oy, d, d));
        g.setStroke(new BasicStroke(on ? 4f : 3f));
        g.setColor(on ? Ui.TEAL : Ui.MIST);
        g.draw(new Ellipse2D.Double(cx - d / 2.0 + 2, oy + 2, d - 4, d - 4));
        g.setComposite(AlphaComposite.SrcOver.derive(on ? 1f : 0.35f));
        g.setFont(Ui.font(Font.PLAIN, d * 0.42f));
        g.setColor(Ui.INK);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(b.icon(), (float) (cx - fm.stringWidth(b.icon()) / 2.0), (float) (cy + (fm.getAscent() - fm.getDescent()) / 2.0));
        g.setComposite(AlphaComposite.SrcOver);
        if (!on) lock(g, cx + d * 0.34, oy + d * 0.80, d * 0.13);
        g.setTransform(keep);

        // name plate
        int pw = Math.min(w - 20, 260);
        double px = cx - pw / 2.0, py = oy + d + gap;
        g.setColor(on ? Ui.INK : Ui.MIST);
        g.fill(new RoundRectangle2D.Double(px, py, pw, plateH, 22, 22));
        g.setFont(Ui.font(Font.BOLD, 17));
        g.setColor(on ? Color.WHITE : Ui.INK);
        Ui.drawCentered(g, b.name(), cx, py + 27);
        g.setFont(Ui.font(Font.PLAIN, 13));
        g.setColor(on ? Ui.MIST : Ui.MUTED);
        Ui.drawCentered(g, b.desc(), cx, py + 47);
        g.dispose();
    }

    /** Tiny padlock badge for locked tiles. */
    private static void lock(Graphics2D g, double x, double y, double r) {
        g.setColor(Ui.INK);
        g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
        g.setColor(Color.WHITE);
        double bw = r * 0.95, bh = r * 0.7, by = y - bh / 2 + r * 0.18;
        g.fill(new RoundRectangle2D.Double(x - bw / 2, by, bw, bh, 3, 3));
        g.setStroke(new BasicStroke((float) (r * 0.17)));
        g.draw(new Arc2D.Double(x - bw * 0.3, by - bw * 0.5, bw * 0.6, bw * 0.7, 0, 180, Arc2D.OPEN));
    }
}

/* ===================== TREE IN A CIRCLE (timer page) ===================== */
class TreeCanvas extends JComponent {
    private static final int MAX_DEPTH = 5;
    private static final Color[] LEAVES = {Ui.TEAL_MID, Ui.TEAL, new Color(0x6BA892)};
    private final Garden garden;
    private double shownKg, sc, t;

    TreeCanvas(Garden garden, int size) {
        this.garden = garden;
        Ui.fixed(this, size, size);
        new Timer(16, e -> { shownKg += (garden.total() - shownKg) * 0.05; repaint(); }).start();
    }

    /** kg progress into the tree that is currently growing. */
    double currentTreeKg() {
        double c = shownKg % Config.FULL_TREE_KG;
        return (shownKg > 1e-6 && c < 1e-6) ? Config.FULL_TREE_KG : c;
    }

    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        t = System.nanoTime() / 1e9;
        int w = getWidth(), h = getHeight(), d = Math.min(w, h) - 10;
        double ox = (w - d) / 2.0, oy = (h - d) / 2.0;
        Ellipse2D circle = new Ellipse2D.Double(ox, oy, d, d);

        g.setColor(new Color(75, 92, 86, 24));                         // soft shadow
        g.fill(new Ellipse2D.Double(ox + 2, oy + 7, d, d));
        g.setPaint(new GradientPaint(0, (float) oy, Color.WHITE, 0, (float) (oy + d), Ui.PALE));
        g.fill(circle);
        g.setClip(circle);
        g.setColor(new Color(255, 255, 255, 200));                     // sun / glow
        g.fill(new Ellipse2D.Double(ox + d * 0.68, oy + d * 0.20, d * 0.16, d * 0.16));
        double gy = oy + d * 0.84;
        g.setColor(Ui.MIST);                                           // back hill
        g.fill(new Ellipse2D.Double(ox - d * 0.45, gy - d * 0.07, d * 1.2, d));
        g.setColor(Ui.SOFT);                                           // front hill
        g.fill(new Ellipse2D.Double(ox - d * 0.2, gy - 6, d * 1.4, d));

        sc = d / 260.0 * 0.78;
        double gr = currentTreeKg() / Config.FULL_TREE_KG;
        if (gr < 0.04) {
            g.setColor(Ui.TRUNK);
            g.fill(new Ellipse2D.Double(w / 2.0 - 6 * sc, gy - 8 * sc, 12 * sc, 9 * sc));
        } else {
            branch(g, w / 2.0, gy, (10 + 58 * gr) * sc, 0, 0, gr);
        }
        g.setClip(null);

        // label at the top of the circle, as in the sketch
        g.setFont(Ui.font(Font.BOLD, 17));
        g.setColor(Ui.INK);
        Ui.drawCentered(g, String.format("%.1f kg CO₂", shownKg), w / 2.0, oy + d * 0.15);

        g.setStroke(new BasicStroke(4f));
        g.setColor(Ui.INK);
        g.draw(new Ellipse2D.Double(ox + 2, oy + 2, d - 4, d - 4));
        g.dispose();
    }

    private void branch(Graphics2D g, double x, double y, double len, double ang, int d, double gr) {
        double s = d == 0 ? 1 : Math.max(0, Math.min(1, gr * 6 - d + 0.5));   // new branches grow in gradually
        if (s <= 0) return;
        ang += Math.sin(t * 1.2 + d) * 0.025 * d;                                // gentle sway
        double l = len * s, x2 = x + Math.sin(ang) * l, y2 = y - Math.cos(ang) * l;
        g.setStroke(new BasicStroke((float) Math.max(1.5, len * 0.13), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Ui.TRUNK);
        g.draw(new Line2D.Double(x, y, x2, y2));
        if (d < MAX_DEPTH) {
            branch(g, x2, y2, len * 0.72, ang - 0.55, d + 1, gr);
            branch(g, x2, y2, len * 0.72, ang + 0.5, d + 1, gr);
        }
        if (d >= 2) {
            double r = (5 + 7 * gr) * sc * s * (d == MAX_DEPTH ? 1.3 : 1);
            Color c = LEAVES[d % 3];
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 230));
            g.fill(new Ellipse2D.Double(x2 - r, y2 - r, 2 * r, 2 * r));
        }
    }
}

/* ===================== ISOMETRIC FOREST (forest page) ===================== */
class ForestCanvas extends JComponent {
    private static final int N = Config.FOREST_GRID;
    // order the tiles get planted in (spreads trees out from the middle)
    private static final int[][] SLOTS = {{1, 1}, {2, 2}, {1, 2}, {2, 1}, {0, 1}, {3, 2}, {2, 3}, {1, 0},
        {3, 1}, {0, 2}, {2, 0}, {1, 3}, {3, 3}, {0, 0}, {3, 0}, {0, 3}};
    private final Garden garden;
    private double shown;

    ForestCanvas(Garden garden) {
        this.garden = garden;
        new Timer(30, e -> { shown += (garden.total() - shown) * 0.08; if (isShowing()) repaint(); }).start();
    }

    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        double t = System.nanoTime() / 1e9;
        int w = getWidth(), h = getHeight();
        double thick = 30;
        double tw = Math.min(w * 0.72 / N, (h * 0.80 - thick) / (N / 2.0 + 0.45));   // tile width
        double th = tw / 2, cx = w / 2.0;
        double top = (h - (N * th + thick + tw * 0.4)) / 2 + tw * 0.4 - 18;

        // slab sides
        Point2D l = p(cx, top, tw, th, 0, N), b = p(cx, top, tw, th, N, N), r = p(cx, top, tw, th, N, 0);
        g.setColor(new Color(75, 92, 86, 30));                                    // ground shadow
        g.fill(poly(l.getX() + 10, l.getY() + thick + 6, b.getX(), b.getY() + thick + 16,
            r.getX() - 10, r.getY() + thick + 6, b.getX(), b.getY() + thick - 4));
        g.setColor(Ui.SOIL_L);
        g.fill(poly(l.getX(), l.getY(), b.getX(), b.getY(), b.getX(), b.getY() + thick, l.getX(), l.getY() + thick));
        g.setColor(Ui.SOIL_R);
        g.fill(poly(r.getX(), r.getY(), b.getX(), b.getY(), b.getX(), b.getY() + thick, r.getX(), r.getY() + thick));

        // checkered grass top (palette greens)
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                Point2D a = p(cx, top, tw, th, i, j), bb = p(cx, top, tw, th, i + 1, j),
                    c = p(cx, top, tw, th, i + 1, j + 1), d = p(cx, top, tw, th, i, j + 1);
                g.setColor(((i + j) & 1) == 0 ? Ui.SOFT : new Color(0x97BDAF));
                g.fill(poly(a.getX(), a.getY(), bb.getX(), bb.getY(), c.getX(), c.getY(), d.getX(), d.getY()));
            }
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Ui.INK);
        Point2D tp = p(cx, top, tw, th, 0, 0);
        g.draw(poly(tp.getX(), tp.getY(), r.getX(), r.getY(), b.getX(), b.getY(), l.getX(), l.getY()));
        g.draw(new Line2D.Double(l.getX(), l.getY(), l.getX(), l.getY() + thick));
        g.draw(new Line2D.Double(b.getX(), b.getY(), b.getX(), b.getY() + thick));
        g.draw(new Line2D.Double(r.getX(), r.getY(), r.getX(), r.getY() + thick));
        g.draw(new Line2D.Double(l.getX(), l.getY() + thick, b.getX(), b.getY() + thick));
        g.draw(new Line2D.Double(b.getX(), b.getY() + thick, r.getX(), r.getY() + thick));

        // trees (back to front)
        int full = (int) (shown / Config.FULL_TREE_KG);
        double part = (shown % Config.FULL_TREE_KG) / Config.FULL_TREE_KG;
        int count = Math.min(N * N, full + (part > 0.01 ? 1 : 0));
        List<double[]> trees = new ArrayList<>();
        for (int k = 0; k < count; k++) trees.add(new double[]{SLOTS[k][0], SLOTS[k][1], k < full ? 1 : part});
        trees.sort(Comparator.comparingDouble((double[] a) -> a[0] + a[1]).thenComparingDouble(a -> a[0]));
        for (double[] tr : trees) {
            Point2D c = p(cx, top, tw, th, tr[0] + 0.5, tr[1] + 0.5);
            pine(g, c.getX(), c.getY(), tw * 0.78, tr[2], t);
        }

        // caption chip
        String cap = count == 0
            ? "Your forest is empty - start a journey to plant your first tree 🌱"
            : full + (full == 1 ? " tree" : " trees") + (part > 0.01 ? " + 1 growing" : "")
                + "  ·  " + String.format("%.1f kg CO₂ saved in total", garden.total());
        g.setFont(Ui.font(Font.BOLD, 16));
        FontMetrics fm = g.getFontMetrics();
        int cw = fm.stringWidth(cap) + 44, ch = 40;
        g.setColor(new Color(255, 255, 255, 235));
        g.fillRoundRect((w - cw) / 2, h - ch - 22, cw, ch, ch, ch);
        g.setColor(Ui.INK);
        Ui.drawCentered(g, cap, cx, h - 22 - ch / 2.0 + (fm.getAscent() - fm.getDescent()) / 2.0);
        g.dispose();
    }

    private static Point2D p(double cx, double top, double tw, double th, double u, double v) {
        return new Point2D.Double(cx + (u - v) * tw / 2, top + (u + v) * th / 2);
    }

    private static Path2D poly(double... xy) {
        Path2D path = new Path2D.Double();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.closePath();
        return path;
    }

    private void pine(Graphics2D g, double x, double y, double ht, double gr, double t) {
        double H = ht * (0.25 + 0.75 * gr);
        g.setColor(new Color(47, 107, 87, 55));
        g.fill(new Ellipse2D.Double(x - H * 0.28, y - H * 0.05, H * 0.56, H * 0.1));
        double trunkW = H * 0.09, trunkH = H * 0.22;
        g.setColor(Ui.TRUNK);
        g.fill(new Rectangle2D.Double(x - trunkW / 2, y - trunkH, trunkW, trunkH));
        Color[] cols = {Ui.TEAL_DARK, Ui.TEAL_MID, Ui.TEAL};
        for (int k = 0; k < 3; k++) {
            double by = y - trunkH * 0.6 - k * H * 0.24, bw = H * 0.55 * (1 - k * 0.22), hh = H * 0.42;
            double sway = Math.sin(t * 1.5 + x * 0.01) * H * 0.015 * k;
            g.setColor(cols[k]);
            g.fill(poly(x - bw / 2, by, x + bw / 2, by, x + sway, by - hh));
        }
    }
}

/* ===================== APP SHELL ===================== */
final class AppFrame extends JFrame {
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final JourneyTracker tracker = new MockJourneyTracker();   // <- swap for real API implementation
    private Journey journey = new Journey("Edinburgh", "Glasgow", 75, 60);   // default until the user picks a real one
    private final Garden garden = new Garden();
    private final TimerPage timerPage = new TimerPage(garden, journey, this::go, this::toggleJourney);
    private final ForestPage forestPage = new ForestPage(garden, this::go);
    private final BadgesPage badgesPage = new BadgesPage(garden, this::go);
    private boolean running;

    AppFrame() {
        super("Coach CO₂ Tracker");
        root.add(timerPage, "timer");
        root.add(forestPage, "forest");
        root.add(badgesPage, "badges");
        setContentPane(root);
        setSize(1200, 780);
        setMinimumSize(new Dimension(1000, 720));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
    }

    private void go(String page) {
        if (page.equals("journey")) { chooseJourney(); return; }
        badgesPage.refresh();
        cards.show(root, page);
    }

    private void chooseJourney() {
        if (running) {
            JOptionPane.showMessageDialog(this, "Finish your current journey first.");
            return;
        }
        new JourneyDialog(this, j -> { journey = j; timerPage.setJourney(j); }).setVisible(true);
    }

    private void toggleJourney() {
        if (!running) {
            garden.liveKg = 0;
            running = true;
            timerPage.begin(journey);
            timerPage.setRunning(true);
            tracker.start(journey, this::onSnapshot);
        } else {
            Snapshot s = tracker.stop();                     // final stats (in-memory "save")
            running = false;
            garden.bank();
            timerPage.setRunning(false);
            timerPage.finish(s);
            badgesPage.refresh();
        }
    }

    private void onSnapshot(Snapshot s) {
        garden.liveKg = s.co2Kg();
        timerPage.update(s, garden.checkUnlocks());
        badgesPage.refresh();
    }
}