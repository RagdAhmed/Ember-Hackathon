import java.awt.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.Timer;

/** Page 3 of the sketch (shown first): menu, tree in a circle, 00:00 timer, START JOURNEY. */
final class TimerPage extends SkyPanel {
    private Journey journey;
    private final TreeCanvas tree;
    private final JLabel stage = Ui.label("Seed", 15, Font.BOLD, Ui.TEAL_DARK);
    private final BigTime timer = new BigTime();
    private final JLabel route = Ui.label(" ", 15, Font.PLAIN, Ui.MUTED);
    private final Bar bar = new Bar();
    private final Toast toast = new Toast();
    private final JLabel userLabel = Ui.label(" ", 13, Font.BOLD, Ui.TEAL_DARK);
    private final PillButton button = new PillButton("SELECT JOURNEY", Ui.TEAL, 340);

    TimerPage(Garden garden, Journey j, Runnable openMenu, Runnable onToggle) {
        this.journey = j;
        tree = new TreeCanvas(garden, 260);
        setLayout(new BorderLayout());

        // top bar: hamburger menu + app name
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(16, 20, 4, 20));
        JPanel menu = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));    // same width as the right side so the title stays centred
        menu.setOpaque(false);
        menu.setPreferredSize(new Dimension(150, 44));
        menu.add(new MenuButton(openMenu));
        top.add(menu, BorderLayout.WEST);
        JLabel title = new JLabel("Coach CO₂ Tracker", SwingConstants.CENTER);
        title.setFont(Ui.font(Font.BOLD, 17));
        title.setForeground(Ui.MUTED);
        top.add(title, BorderLayout.CENTER);
        userLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        userLabel.setPreferredSize(new Dimension(150, 44));
        top.add(userLabel, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        // single centred column, as in the sketch
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.add(tree);
        col.add(Ui.gap(8));
        col.add(stage);
        col.add(Ui.gap(2));
        col.add(timer);
        col.add(Ui.gap(2));
        col.add(Ui.fixed(route, 460, 22));
        col.add(Ui.gap(10));
        col.add(bar);
        col.add(Ui.gap(22));
        button.addActionListener(e -> onToggle.run());
        col.add(button);
        col.add(Ui.gap(14));
        col.add(toast);

        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        center.add(col);
        add(center, BorderLayout.CENTER);

        resetStats();
        setRunning(false);
        new Timer(100, e -> stage.setText(Config.stage(tree.currentTreeKg()))).start();
    }

    private String idleRoute() {
        if (journey == null) return "No journey selected yet";
        return journey.from() + " → " + journey.to() + "  ·  " + (int) journey.distanceKm() + " km  ·  ≈ "
            + journey.durationMin() + " min";
    }

    private void resetStats() {
        timer.setText("00:00");
        bar.set(0);
        route.setText(idleRoute());
    }

    void setRunning(boolean running) {
        button.setText(running ? "I'VE GOT OFF THE COACH" : journey == null ? "SELECT JOURNEY" : "START JOURNEY");
        button.setColor(running ? Ui.INK : Ui.TEAL);
    }

    /** Shown top-right; null means guest. */
    void setUser(String name) { userLabel.setText(name == null ? "Guest" : "👤 " + name); }

    /** Forget the selected journey (used on login/logout). */
    void clearJourney() {
        journey = null;
        resetStats();
        setRunning(false);
        toast.clear();
    }

    /** Switch to a different (real) journey picked from the API. */
    void setJourney(Journey j) {
        journey = j;
        resetStats();
        setRunning(false);
        toast.clear();
        toast.show("🚌 Journey set: " + j.from() + " → " + j.to());
    }

    void begin(Journey j) {
        resetStats();
        toast.clear();
        toast.show("✅ Journey started: " + j.from() + " → " + j.to());
    }

    void finish(Snapshot s) {
        toast.show(String.format("🌳 Saved %.1f kg CO₂ - added to your forest", s.co2Kg()));
    }

    void update(Snapshot s, List<Badge> newBadges) {
        timer.setText(String.format("%02d:%02d", s.elapsedSec() / 60, s.elapsedSec() % 60));
        bar.set(s.progress());
        route.setText(String.format("%s → %s  ·  %.1f / %.0f km", journey.from(), journey.to(),
            s.distanceKm(), s.totalKm()));
        for (Badge b : newBadges) toast.show(b.icon() + " Badge unlocked: " + b.name());
        if (s.progress() >= 1) toast.show("📍 You've arrived in " + journey.to() + "!");
    }
}