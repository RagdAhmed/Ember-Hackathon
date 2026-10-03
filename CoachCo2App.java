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
    static final int DEMO_SECONDS = 30;            // real seconds the simulated journey takes
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
            double p = Math.min(1, (System.currentTimeMillis() - startMs) / (Config.DEMO_SECONDS * 1000.0));
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
    static final Color DEEP = new Color(0x1B5E20), GREEN = new Color(0x2E7D32), LEAF = new Color(0x66BB6A),
        MINT = new Color(0xE8F5E9), TRUNK = new Color(0x6D4C41), TEXT = new Color(0x1F2D1F), MUTED = new Color(0x6B7F6B);

    static Font font(int style, float size) { return new Font("SansSerif", style, (int) size).deriveFont(style, size); }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
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

    /** Top bar used by the Forest and Badges pages:  <-   [ Forest | Badges ] */
    static JPanel topBar(String active, Consumer<String> nav) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(16, 20, 8, 20));
        JButton back = new JButton("←");
        back.setFont(font(Font.BOLD, 28));
        back.setForeground(DEEP);
        back.setContentAreaFilled(false);
        back.setBorderPainted(false);
        back.setFocusPainted(false);
        back.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        back.setPreferredSize(new Dimension(56, 44));
        back.addActionListener(e -> nav.accept("timer"));
        JPanel mid = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        mid.setOpaque(false);
        mid.add(new Pill(nav, active.equals("forest") ? 0 : 1));
        bar.add(back, BorderLayout.WEST);
        bar.add(mid, BorderLayout.CENTER);
        bar.add(Box.createRigidArea(new Dimension(56, 44)), BorderLayout.EAST);
        return bar;
    }
}

class SkyPanel extends JPanel {
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setPaint(new GradientPaint(0, 0, new Color(0xCDEBD3), 0, getHeight(), new Color(0xF7FBF4)));
        g.fillRect(0, 0, getWidth(), getHeight());
    }
}

class Card extends JPanel {
    Card() { setOpaque(false); setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16)); }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.setColor(new Color(255, 255, 255, 225));
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
        g.dispose();
    }
}

class Stat extends Card {
    private final JLabel v = new JLabel("-", SwingConstants.CENTER);
    Stat(String title) {
        setLayout(new GridLayout(2, 1));
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        v.setFont(Ui.font(Font.BOLD, 20));
        v.setForeground(Ui.DEEP);
        JLabel t = new JLabel(title, SwingConstants.CENTER);
        t.setFont(Ui.font(Font.PLAIN, 12));
        t.setForeground(Ui.MUTED);
        add(v);
        add(t);
    }
    void set(String s) { v.setText(s); }
}

class RoundButton extends JButton {
    private Color c;
    RoundButton(String text, Color c, int w) {
        super(text);
        this.c = c;
        setFont(Ui.font(Font.BOLD, 18));
        setForeground(Color.WHITE);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Ui.fixed(this, w, 56);
    }
    void setColor(Color c) { this.c = c; repaint(); }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.setColor(getModel().isPressed() ? c.darker() : c);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 32, 32);
        g.dispose();
        super.paintComponent(g0);
    }
}

class Bar extends JComponent {
    private double v;
    Bar() { Ui.fixed(this, 360, 14); }
    void set(double p) { v = p; repaint(); }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.setColor(new Color(0xD5E8D8));
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
        g.setPaint(new GradientPaint(0, 0, Ui.LEAF, getWidth(), 0, Ui.GREEN));
        g.fillRoundRect(0, 0, (int) (getWidth() * v), getHeight(), 14, 14);
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
        Ui.fixed(this, 360, 36);
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
        g.setColor(Ui.DEEP);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 30, 30);
        g.setColor(Color.WHITE);
        g.setFont(Ui.font(Font.BOLD, 14));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
    }
}

/** The "Forest | Badges" switch from the sketch. */
class Pill extends JComponent {
    private final int active;
    Pill(Consumer<String> nav, int active) {
        this.active = active;
        Ui.fixed(this, 340, 44);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { nav.accept(e.getX() < getWidth() / 2 ? "forest" : "badges"); }
        });
    }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        int w = getWidth(), h = getHeight(), half = w / 2;
        g.setColor(new Color(255, 255, 255, 230));
        g.fillRoundRect(0, 0, w - 1, h - 1, h, h);
        g.setColor(Ui.DEEP);
        g.fillRoundRect(active == 0 ? 3 : half, 3, half - 3, h - 7, h - 6, h - 6);
        g.setFont(Ui.font(Font.BOLD, 16));
        FontMetrics fm = g.getFontMetrics();
        int base = (h + fm.getAscent() - fm.getDescent()) / 2;
        g.setColor(active == 0 ? Color.WHITE : Ui.DEEP);
        Ui.drawCentered(g, "Forest", half / 2.0, base);
        g.setColor(active == 1 ? Color.WHITE : Ui.DEEP);
        Ui.drawCentered(g, "Badges", half + half / 2.0, base);
        g.dispose();
    }
}

/** Hamburger menu (top-left of the timer page). */
class MenuButton extends JButton {
    MenuButton(Consumer<String> nav) {
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(56, 44));
        JPopupMenu pm = new JPopupMenu();
        for (String[] item : new String[][]{{"🌲  Forest", "forest"}, {"🏅  Badges", "badges"}}) {
            JMenuItem mi = new JMenuItem(item[0]);
            mi.setFont(Ui.font(Font.PLAIN, 16));
            mi.addActionListener(e -> nav.accept(item[1]));
            pm.add(mi);
        }
        addActionListener(e -> pm.show(this, 0, getHeight()));
    }
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.setColor(Ui.DEEP);
        for (int i = 0; i < 3; i++) g.fillRoundRect(12, 11 + i * 9, 30, 4, 4, 4);
        g.dispose();
    }
}

/* ===================== BADGE TILE (circle + name) ===================== */
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
        int w = getWidth(), h = getHeight(), textH = 70;
        int d = Math.max(40, Math.min(w - 20, h - textH));
        double cx = w / 2.0, oy = Math.max(0, (h - textH - d) / 2.0), cy = oy + d / 2.0;

        AffineTransform keep = g.getTransform();
        double s = 1 + 0.15 * Math.sin(pop * Math.PI);
        g.translate(cx, cy);
        g.scale(s, s);
        g.translate(-cx, -cy);
        g.setColor(on ? new Color(0xC8E6C9) : new Color(0xE3E8E3));
        g.fill(new Ellipse2D.Double(cx - d / 2.0, oy, d, d));
        g.setStroke(new BasicStroke(3f));
        g.setColor(on ? Ui.DEEP : new Color(0xB5BEB5));
        g.draw(new Ellipse2D.Double(cx - d / 2.0, oy, d, d));
        g.setComposite(AlphaComposite.SrcOver.derive(on ? 1f : 0.35f));
        g.setFont(Ui.font(Font.PLAIN, d * 0.42f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(b.icon(), (float) (cx - fm.stringWidth(b.icon()) / 2.0), (float) (cy + (fm.getAscent() - fm.getDescent()) / 2.0));
        g.setComposite(AlphaComposite.SrcOver);
        g.setTransform(keep);

        double ty = oy + d + 24;
        g.setColor(on ? Ui.DEEP : Ui.MUTED);
        g.setFont(Ui.font(Font.BOLD, 16));
        Ui.drawCentered(g, b.name(), cx, ty);
        g.setFont(Ui.font(Font.PLAIN, 12));
        g.setColor(Ui.MUTED);
        Ui.drawCentered(g, b.desc(), cx, ty + 18);
        g.setFont(Ui.font(Font.BOLD, 12));
        g.setColor(on ? Ui.GREEN : Ui.MUTED);
        Ui.drawCentered(g, on ? "Unlocked ✓" : "🔒 Locked", cx, ty + 36);
        g.dispose();
    }
}

/* ===================== TREE IN A CIRCLE (timer page) ===================== */
class TreeCanvas extends JComponent {
    private static final int MAX_DEPTH = 5;
    private static final Color[] LEAVES = {new Color(0x43A047), new Color(0x66BB6A), new Color(0x81C784)};
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
        int w = getWidth(), h = getHeight(), d = Math.min(w, h) - 8;
        double ox = (w - d) / 2.0, oy = (h - d) / 2.0;
        Ellipse2D circle = new Ellipse2D.Double(ox, oy, d, d);

        g.setPaint(new GradientPaint(0, (float) oy, new Color(0xE3F4E6), 0, (float) (oy + d), new Color(0xFFFFFF)));
        g.fill(circle);
        g.setClip(circle);
        g.setColor(new Color(255, 236, 160, 220));
        g.fill(new Ellipse2D.Double(ox + d * 0.70, oy + d * 0.10, d * 0.17, d * 0.17));
        double gy = oy + d * 0.84;
        g.setColor(new Color(0x9CCC65));
        g.fill(new Ellipse2D.Double(ox - d * 0.2, gy - 8, d * 1.4, d));
        sc = d / 260.0;
        double gr = currentTreeKg() / Config.FULL_TREE_KG;
        if (gr < 0.04) {
            g.setColor(Ui.TRUNK);
            g.fill(new Ellipse2D.Double(w / 2.0 - 6 * sc, gy - 8 * sc, 12 * sc, 9 * sc));
        } else {
            branch(g, w / 2.0, gy, (10 + 58 * gr) * sc, 0, 0, gr);
        }
        g.setClip(null);
        g.setStroke(new BasicStroke(4f));
        g.setColor(Ui.DEEP);
        g.draw(circle);
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
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 225));
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
        double thick = 26;
        double tw = Math.min(w * 0.72 / N, (h * 0.85 - thick) / (N / 2.0 + 0.45));   // tile width
        double th = tw / 2, cx = w / 2.0;
        double top = (h - (N * th + thick + tw * 0.4)) / 2 + tw * 0.4 - 10;

        // slab sides
        Point2D l = p(cx, top, tw, th, 0, N), b = p(cx, top, tw, th, N, N), r = p(cx, top, tw, th, N, 0);
        g.setColor(new Color(0x8D6E63));
        g.fill(poly(l.getX(), l.getY(), b.getX(), b.getY(), b.getX(), b.getY() + thick, l.getX(), l.getY() + thick));
        g.setColor(new Color(0x5D4037));
        g.fill(poly(r.getX(), r.getY(), b.getX(), b.getY(), b.getX(), b.getY() + thick, r.getX(), r.getY() + thick));

        // checkered grass top
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                Point2D a = p(cx, top, tw, th, i, j), bb = p(cx, top, tw, th, i + 1, j),
                    c = p(cx, top, tw, th, i + 1, j + 1), d = p(cx, top, tw, th, i, j + 1);
                g.setColor(((i + j) & 1) == 0 ? new Color(0xA5D66F) : new Color(0x98CA5E));
                g.fill(poly(a.getX(), a.getY(), bb.getX(), bb.getY(), c.getX(), c.getY(), d.getX(), d.getY()));
            }
        g.setStroke(new BasicStroke(2f));
        g.setColor(Ui.DEEP);
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
            pine(g, c.getX(), c.getY(), tw * 0.9, tr[2], t);
        }

        // caption
        g.setFont(Ui.font(Font.BOLD, 16));
        g.setColor(Ui.DEEP);
        String cap = count == 0
            ? "Your forest is empty - start a journey to plant your first tree 🌱"
            : full + (full == 1 ? " tree" : " trees") + (part > 0.01 ? " + 1 growing" : "")
                + "  ·  " + String.format("%.1f kg CO₂ saved in total", garden.total());
        Ui.drawCentered(g, cap, cx, h - 18);
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
        g.setColor(new Color(0, 0, 0, 45));
        g.fill(new Ellipse2D.Double(x - H * 0.28, y - H * 0.05, H * 0.56, H * 0.1));
        double trunkW = H * 0.09, trunkH = H * 0.22;
        g.setColor(Ui.TRUNK);
        g.fill(new Rectangle2D.Double(x - trunkW / 2, y - trunkH, trunkW, trunkH));
        Color[] cols = {new Color(0x2E7D32), new Color(0x43A047), new Color(0x66BB6A)};
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
    private final Journey journey = new Journey("Edinburgh", "Glasgow", 75, 60);
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
        badgesPage.refresh();
        cards.show(root, page);
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
