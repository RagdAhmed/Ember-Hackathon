import java.awt.*;
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
    static final double CAR_G_PER_MILE = 278;      // average car tailpipe CO2, grams per mile
    static final double COACH_G_PER_MILE = 0;      // electric coach: zero tailpipe emissions
    static final double KM_PER_MILE = 1.609344;
    static final boolean REAL_TIME = false;        // false: replay a journey in DEMO_SECONDS; true: run it in real time
    static final int DEMO_SECONDS = 30;            // real seconds the replayed journey takes (when REAL_TIME is false)
    static final double ROAD_FACTOR = 1.15;        // road distance ~ straight-line distance x this (API gives no distance)
    static final double FULL_TREE_KG = 18.6;       // kg that makes one fully grown tree
    static final int FOREST_GRID = 4;              // forest plot is GRID x GRID tiles (max trees = GRID^2)
    static final double[] STAGE_KG = {0, 1, 5, 10, 16};
    static final String[] STAGE_NAME = {"Seed", "Sprout", "Young tree", "Mature tree", "Large tree"};
    static final double FOREST_CAPACITY_KG = FOREST_GRID * FOREST_GRID * FULL_TREE_KG;   // kg that fills one whole map
    /** Maps are played in order and loop. Add another ForestMap here to add a map. */
    static final List<ForestMap> MAPS = List.of(
        new ForestMap("Meadow Forest", "🌲", Ui.PALE, new Color(0xEEF5F2), Ui.SOFT, new Color(0x97BDAF),
            Ui.SOIL_L, Ui.SOIL_R, Ui.TRUNK, new Color[]{Ui.TEAL_DARK, Ui.TEAL_MID, Ui.TEAL}, Ui.INK, Ui.TEAL_DARK, false),
        new ForestMap("Halloween Forest", "🎃", new Color(0xFFDDB8), new Color(0xCDB6E6), new Color(0x6E5A8E), new Color(0x604E80),
            new Color(0x4A3340), new Color(0x372531), new Color(0x2E2230),
            new Color[]{new Color(0x2A1F3D), new Color(0x3F2D5C), new Color(0x5A3F7D)}, new Color(0x2A1F3D), new Color(0xD9680F), true));
    static final List<Badge> BADGES = List.of(
        new Badge("🌱", "First Sprout", "First CO₂ saved", 0.01),
        new Badge("🌿", "Growing Green", "10 kg CO₂ saved", 10),
        new Badge("🌳", "Tree Planter", "50 kg CO₂ saved", 50),
        new Badge("🌲", "Forest Builder", "100 kg CO₂ saved", 100),
        new Badge("🌍", "Planet Protector", "150 kg CO₂ saved", 150),
        new Badge("🏆", "Forest Guardian", "250 kg CO₂ saved", 250));

    static int journeySeconds(Journey j) { return REAL_TIME ? j.durationMin() * 60 : DEMO_SECONDS; }

    /** CO2 saved (kg) by taking the electric coach instead of driving the same distance. */
    static double savedKg(double km) { return (km / KM_PER_MILE) * (CAR_G_PER_MILE - COACH_G_PER_MILE) / 1000.0; }

    static String stage(double kg) {
        for (int i = STAGE_KG.length - 1; i >= 0; i--) if (kg >= STAGE_KG[i]) return STAGE_NAME[i];
        return STAGE_NAME[0];
    }
}

/* ===================== DATA / TRACKING LAYER ===================== */
record Journey(String from, String to, double distanceKm, int durationMin) {}
record Badge(String icon, String name, String desc, double kg) {}
/** Look of one forest map (colours + whether it gets the spooky extras). */
record ForestMap(String name, String icon, Color skyTop, Color skyBottom, Color grassA, Color grassB,
                 Color soilL, Color soilR, Color trunk, Color[] foliage, Color outline, Color accent, boolean spooky) {}
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

    /** Which map the forest is on. Each map takes FOREST_CAPACITY_KG; kg beyond that carries over to the next map. */
    int mapIndex;

    ForestMap map() { return Config.MAPS.get(mapIndex % Config.MAPS.size()); }
    ForestMap nextMapPreview() { return Config.MAPS.get((mapIndex + 1) % Config.MAPS.size()); }
    double mapKg() { return Math.max(0, total() - mapIndex * Config.FOREST_CAPACITY_KG); }
    boolean forestFull() { return mapKg() >= Config.FOREST_CAPACITY_KG - 1e-9; }
    void nextMap() { if (forestFull()) mapIndex++; }

    /** Replace everything with saved progress (badges are re-derived from the total). */
    void load(double kg, int map) {
        bankedKg = kg;
        liveKg = 0;
        mapIndex = Math.max(0, Math.min(map, (int) (kg / Config.FOREST_CAPACITY_KG)));   // can't be on a map you haven't filled the last one for
        unlocked.clear();
        checkUnlocks();
    }

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
    private Color top, bottom;
    SkyPanel() { this(false); }
    SkyPanel(boolean tinted) {
        top = tinted ? Ui.PALE : Color.WHITE;
        bottom = tinted ? new Color(0xEEF5F2) : new Color(0xE9F1EE);
    }
    void setTheme(Color top, Color bottom) { this.top = top; this.bottom = bottom; repaint(); }
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

/** Hamburger button (top-left of the timer page) - opens the side menu. */
class MenuButton extends JButton {
    MenuButton(Runnable openMenu) {
        Ui.plainButton(this, 56, 44);
        setToolTipText("Menu");
        addActionListener(e -> openMenu.run());
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
        Ui.drawCentered(g, "saved", w / 2.0, oy + d * 0.15 + 20);

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
    private static final int BANNER = 56;      // room reserved for the "forest saved" banner
    // order the tiles get planted in (spreads trees out from the middle)
    private static final int[][] SLOTS = {{1, 1}, {2, 2}, {1, 2}, {2, 1}, {0, 1}, {3, 2}, {2, 3}, {1, 0},
        {3, 1}, {0, 2}, {2, 0}, {1, 3}, {3, 3}, {0, 0}, {3, 0}, {0, 3}};
    private final Garden garden;
    private double shown, banner;
    private int shownMap;

    ForestCanvas(Garden garden) {
        this.garden = garden;
        shownMap = garden.mapIndex;
        new Timer(30, e -> {
            if (garden.mapIndex != shownMap) { shownMap = garden.mapIndex; shown = 0; }   // new map: trees grow in from empty
            double target = Math.min(Config.FOREST_CAPACITY_KG, garden.mapKg());
            shown = Math.abs(target - shown) < 0.005 ? target : shown + (target - shown) * 0.08;
            double want = garden.forestFull() ? 1 : 0;
            banner = Math.abs(want - banner) < 0.005 ? want : banner + (want - banner) * 0.1;
            if (isShowing()) repaint();
        }).start();
    }

    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        ForestMap m = garden.map();
        double t = System.nanoTime() / 1e9;
        int w = getWidth(), h = getHeight();
        double thick = 30;
        double tw = Math.min(w * 0.72 / N, (h * 0.80 - thick - BANNER * banner) / (N / 2.0 + 0.45));   // tile width
        double th = tw / 2, cx = w / 2.0;
        double top = (h - (N * th + thick + tw * 0.4)) / 2 + tw * 0.4 - 18 + BANNER * 0.5 * banner;

        if (m.spooky()) night(g, w, h, t);

        // slab sides
        Point2D l = p(cx, top, tw, th, 0, N), b = p(cx, top, tw, th, N, N), r = p(cx, top, tw, th, N, 0);
        g.setColor(new Color(75, 92, 86, 30));                                    // ground shadow
        g.fill(poly(l.getX() + 10, l.getY() + thick + 6, b.getX(), b.getY() + thick + 16,
            r.getX() - 10, r.getY() + thick + 6, b.getX(), b.getY() + thick - 4));
        g.setColor(m.soilL());
        g.fill(poly(l.getX(), l.getY(), b.getX(), b.getY(), b.getX(), b.getY() + thick, l.getX(), l.getY() + thick));
        g.setColor(m.soilR());
        g.fill(poly(r.getX(), r.getY(), b.getX(), b.getY(), b.getX(), b.getY() + thick, r.getX(), r.getY() + thick));

        // checkered grass top
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                Point2D a = p(cx, top, tw, th, i, j), bb = p(cx, top, tw, th, i + 1, j),
                    c = p(cx, top, tw, th, i + 1, j + 1), d = p(cx, top, tw, th, i, j + 1);
                g.setColor(((i + j) & 1) == 0 ? m.grassA() : m.grassB());
                g.fill(poly(a.getX(), a.getY(), bb.getX(), bb.getY(), c.getX(), c.getY(), d.getX(), d.getY()));
            }
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(m.outline());
        Point2D tp = p(cx, top, tw, th, 0, 0);
        g.draw(poly(tp.getX(), tp.getY(), r.getX(), r.getY(), b.getX(), b.getY(), l.getX(), l.getY()));
        g.draw(new Line2D.Double(l.getX(), l.getY(), l.getX(), l.getY() + thick));
        g.draw(new Line2D.Double(b.getX(), b.getY(), b.getX(), b.getY() + thick));
        g.draw(new Line2D.Double(r.getX(), r.getY(), r.getX(), r.getY() + thick));
        g.draw(new Line2D.Double(l.getX(), l.getY() + thick, b.getX(), b.getY() + thick));
        g.draw(new Line2D.Double(b.getX(), b.getY() + thick, r.getX(), r.getY() + thick));

        // trees (back to front)
        int full = (int) Math.floor(shown / Config.FULL_TREE_KG + 1e-9);
        double part = Math.max(0, shown / Config.FULL_TREE_KG - full);
        if (full >= N * N) { full = N * N; part = 0; }
        int count = Math.min(N * N, full + (part > 0.01 ? 1 : 0));
        List<double[]> trees = new ArrayList<>();
        for (int k = 0; k < count; k++) trees.add(new double[]{SLOTS[k][0], SLOTS[k][1], k < full ? 1 : part});
        trees.sort(Comparator.comparingDouble((double[] a) -> a[0] + a[1]).thenComparingDouble(a -> a[0]));
        for (double[] tr : trees) {
            Point2D c = p(cx, top, tw, th, tr[0] + 0.5, tr[1] + 0.5);
            pine(g, c.getX(), c.getY(), tw * 0.78, tr[2], t, m);
            if (m.spooky()) pumpkin(g, c.getX() + tw * 0.2, c.getY() + th * 0.12, tw * 0.07 * (0.4 + 0.6 * tr[2]));
        }

        // "forest saved" banner (slides in once the map is full)
        if (banner > 0.02) {
            String msg = "🎉  Congrats! You saved the forest!";
            g.setFont(Ui.display(Font.BOLD, 26));
            FontMetrics bf = g.getFontMetrics();
            int bw = bf.stringWidth(msg) + 60, bh = 48, by = (int) (14 - 20 * (1 - banner));
            g.setComposite(AlphaComposite.SrcOver.derive((float) Math.min(1, banner)));
            g.setColor(m.accent());
            g.fillRoundRect((w - bw) / 2, by, bw, bh, bh, bh);
            g.setColor(Color.WHITE);
            Ui.drawCentered(g, msg, cx, by + bh / 2.0 + (bf.getAscent() - bf.getDescent()) / 2.0);
            g.setComposite(AlphaComposite.SrcOver);
        }

        // caption chip
        String cap = count == 0
            ? "This map is empty - start a journey to plant your first tree 🌱"
            : m.icon() + " " + m.name() + "  ·  " + full + (full == 1 ? " tree" : " trees") + (part > 0.01 ? " + 1 growing" : "")
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

    private void pine(Graphics2D g, double x, double y, double ht, double gr, double t, ForestMap m) {
        double H = ht * (0.25 + 0.75 * gr);
        g.setColor(m.spooky() ? new Color(0, 0, 0, 55) : new Color(47, 107, 87, 55));
        g.fill(new Ellipse2D.Double(x - H * 0.28, y - H * 0.05, H * 0.56, H * 0.1));
        double trunkW = H * 0.09, trunkH = H * 0.22;
        g.setColor(m.trunk());
        g.fill(new Rectangle2D.Double(x - trunkW / 2, y - trunkH, trunkW, trunkH));
        Color[] cols = m.foliage();
        for (int k = 0; k < 3; k++) {
            double by = y - trunkH * 0.6 - k * H * 0.24, bw = H * 0.55 * (1 - k * 0.22), hh = H * 0.42;
            double sway = Math.sin(t * 1.5 + x * 0.01) * H * 0.015 * k;
            g.setColor(cols[k]);
            g.fill(poly(x - bw / 2, by, x + bw / 2, by, x + sway, by - hh));
        }
    }

    /* ---- Halloween extras ---- */

    private static void night(Graphics2D g, int w, int h, double t) {
        double mr = Math.min(w, h) * 0.07, mx = w * 0.82, my = h * 0.17;
        for (int i = 3; i >= 1; i--) {                                              // soft glow
            g.setColor(new Color(255, 241, 201, 22));
            g.fill(new Ellipse2D.Double(mx - mr - i * 14, my - mr - i * 14, 2 * (mr + i * 14), 2 * (mr + i * 14)));
        }
        g.setColor(new Color(0xFFF4D6));
        g.fill(new Ellipse2D.Double(mx - mr, my - mr, 2 * mr, 2 * mr));
        g.setColor(new Color(0xF2DDB0));
        g.fill(new Ellipse2D.Double(mx - mr * 0.5, my - mr * 0.4, mr * 0.35, mr * 0.35));
        g.fill(new Ellipse2D.Double(mx + mr * 0.15, my + mr * 0.2, mr * 0.45, mr * 0.45));
        bat(g, w * 0.20 + Math.sin(t * 0.5) * 24, h * 0.22 + Math.cos(t * 0.8) * 10, 16, t);
        bat(g, w * 0.34 + Math.sin(t * 0.4 + 2) * 18, h * 0.12 + Math.cos(t * 0.7) * 8, 11, t + 1);
        bat(g, w * 0.68 + Math.sin(t * 0.45 + 4) * 22, h * 0.32 + Math.cos(t * 0.6) * 9, 13, t + 2);
    }

    private static void bat(Graphics2D g, double x, double y, double s, double t) {
        double f = Math.sin(t * 6 + x) * 0.35;                                       // wing flap
        Path2D p = new Path2D.Double();
        p.moveTo(x, y - s * 0.15);
        p.curveTo(x - s * 0.35, y - s * (0.55 + f), x - s * 0.8, y - s * (0.35 + f), x - s, y + s * 0.15);
        p.quadTo(x - s * 0.7, y + s * 0.05, x - s * 0.5, y + s * 0.3);
        p.quadTo(x - s * 0.25, y + s * 0.1, x, y + s * 0.35);
        p.quadTo(x + s * 0.25, y + s * 0.1, x + s * 0.5, y + s * 0.3);
        p.quadTo(x + s * 0.7, y + s * 0.05, x + s, y + s * 0.15);
        p.curveTo(x + s * 0.8, y - s * (0.35 + f), x + s * 0.35, y - s * (0.55 + f), x, y - s * 0.15);
        p.closePath();
        g.setColor(new Color(0x2A1F3D));
        g.fill(p);
    }

    private static void pumpkin(Graphics2D g, double x, double y, double r) {
        g.setColor(new Color(0xD96A0B));
        g.fill(new Ellipse2D.Double(x - r * 1.1, y - r * 0.8, r * 2.2, r * 1.6));
        g.setColor(new Color(0xF58A1F));
        g.fill(new Ellipse2D.Double(x - r * 0.65, y - r * 0.85, r * 1.3, r * 1.7));
        g.setColor(new Color(0x3F5A2A));
        g.fill(new Rectangle2D.Double(x - r * 0.12, y - r * 1.05, r * 0.24, r * 0.35));
    }
}

/* ===================== APP SHELL ===================== */
final class AppFrame extends JFrame {
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final JourneyTracker tracker = new MockJourneyTracker();   // <- swap for real API implementation
    private Journey journey;                                  // null until the user picks one
    private final Garden garden = new Garden();
    private final SideMenu sideMenu = new SideMenu(garden, this::go);
    private final TimerPage timerPage = new TimerPage(garden, journey, sideMenu::open, this::toggleJourney);
    private final ForestPage forestPage = new ForestPage(garden, this::go, this::startNextMap);
    private final BadgesPage badgesPage = new BadgesPage(garden, this::go);
    private boolean running;
    private final Accounts accounts = new Accounts();
    private final LoginPage loginPage = new LoginPage(accounts, this::onLogin, this::onGuest);
    private Accounts.Profile user;                            // null = guest (nothing is saved)
    private int journeys;
    private int toastedMap = -1, dialogMap = -1;             // maps whose "forest full" message was already shown

    AppFrame() {
        super("Coach CO₂ Tracker");
        root.add(loginPage, "login");                         // first card = what shows on launch
        root.add(timerPage, "timer");
        root.add(forestPage, "forest");
        root.add(badgesPage, "badges");
        setContentPane(root);
        setGlassPane(sideMenu);                               // the slide-in menu lives on the glass pane
        setSize(1200, 780);
        setMinimumSize(new Dimension(1000, 720));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (running) finishJourney();                 // don't lose a journey that's in progress
            }
        });
    }

    /* ---- accounts ---- */

    private void onLogin(Accounts.Profile p) {
        user = p;
        journeys = p.journeys();
        garden.load(p.bankedKg(), p.mapIndex());
        startSession(p.username());
    }

    private void onGuest() {
        user = null;
        journeys = 0;
        garden.load(0, 0);
        startSession(null);
    }

    private void startSession(String name) {
        journey = null;
        timerPage.clearJourney();
        timerPage.setUser(name);
        sideMenu.setUser(name);
        forestPage.refresh();
        toastedMap = dialogMap = garden.forestFull() ? garden.mapIndex : -1;   // no pop-ups for a forest that was already full
        badgesPage.refresh();
        cards.show(root, "timer");
    }

    private void logout() {
        if (running) {
            JOptionPane.showMessageDialog(this, "Finish your current journey first.");
            return;
        }
        user = null;
        garden.load(0, 0);
        badgesPage.refresh();
        loginPage.reset();
        cards.show(root, "login");
    }

    private void saveProgress() {
        if (user == null) return;
        try {
            user = new Accounts.Profile(user.username(), garden.bankedKg, journeys, garden.mapIndex);
            accounts.save(user);
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Couldn't save your progress: " + e.getMessage(),
                "Save failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void go(String page) {
        if (page.equals("journey")) { chooseJourney(); return; }
        if (page.equals("logout")) { logout(); return; }
        badgesPage.refresh();
        forestPage.refresh();
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
        if (!running && journey == null) { chooseJourney(); return; }   // nothing picked yet: the button says SELECT JOURNEY
        if (!running) {
            garden.liveKg = 0;
            running = true;
            timerPage.begin(journey);
            timerPage.setRunning(true);
            tracker.start(journey, this::onSnapshot);
        } else {
            Snapshot s = finishJourney();
            journey = null;                                  // back to "select journey" for next time
            timerPage.finish(s);
            if (garden.forestFull() && dialogMap != garden.mapIndex) {
                dialogMap = garden.mapIndex;
                SwingUtilities.invokeLater(this::celebrate);
            }
        }
    }

    private void celebrate() {
        ForestMap next = garden.nextMapPreview();
        int pick = JOptionPane.showOptionDialog(this,
            "🎉  Congratulations! You saved the forest!\n\nYour " + garden.map().name()
                + " is full - every tile has a fully grown tree.\nA new map is waiting for you: " + next.icon() + " " + next.name() + ".",
            "Forest saved!", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
            new String[]{"See my forest", "Later"}, "See my forest");
        if (pick == 0) go("forest");
    }

    /** Called from the Forest page's START NEW MAP button. */
    private void startNextMap() {
        garden.nextMap();
        saveProgress();
        forestPage.refresh();
    }

    /** Stop the coach, bank the CO2 into the garden and save it to the profile. */
    private Snapshot finishJourney() {
        Snapshot s = tracker.stop();
        running = false;
        garden.bank();
        journeys++;
        saveProgress();
        timerPage.setRunning(false);
        badgesPage.refresh();
        return s;
    }

    private void onSnapshot(Snapshot s) {
        garden.liveKg = s.co2Kg();
        timerPage.update(s, garden.checkUnlocks());
        if (garden.forestFull() && toastedMap != garden.mapIndex) {
            toastedMap = garden.mapIndex;
            timerPage.notice("🎉 Your forest is full! Open the Forest for a new map");
        }
        badgesPage.refresh();
    }
}