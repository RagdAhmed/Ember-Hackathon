import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.Timer;

/** Page 3 of the sketch (shown first): menu, tree in circle, 00:00 timer, Start. */
final class TimerPage extends SkyPanel {
    private final TreeCanvas tree;
    private final JLabel stage = Ui.label("Seed", 17, Font.BOLD, Ui.GREEN);
    private final JLabel timer = Ui.label("00:00", 88, Font.BOLD, Ui.DEEP);
    private final JLabel counter = Ui.label("0.0 kg CO₂ saved", 20, Font.PLAIN, Ui.MUTED);
    private final Bar bar = new Bar();
    private final Toast toast = new Toast();
    private final Stat progress = new Stat("Progress"), travelled = new Stat("Travelled"),
        remaining = new Stat("Remaining"), co2 = new Stat("CO₂ saved");
    private final RoundButton button = new RoundButton("Start", Ui.GREEN, 360);
    private double shown, target;

    TimerPage(Garden garden, Journey j, Consumer<String> nav, Runnable onToggle) {
        tree = new TreeCanvas(garden, 280);
        setLayout(new BorderLayout());

        JPanel bar0 = new JPanel(new BorderLayout());
        bar0.setOpaque(false);
        bar0.setBorder(BorderFactory.createEmptyBorder(16, 20, 8, 20));
        bar0.add(new MenuButton(nav), BorderLayout.WEST);
        bar0.add(Ui.label("Coach CO₂ Tracker", 22, Font.BOLD, Ui.DEEP), BorderLayout.CENTER);
        bar0.add(Box.createRigidArea(new Dimension(56, 44)), BorderLayout.EAST);
        add(bar0, BorderLayout.NORTH);

        // left column: tree circle, timer, start button
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(tree);
        left.add(Ui.gap(6));
        left.add(stage);
        left.add(Ui.gap(2));
        left.add(timer);
        left.add(counter);
        left.add(Ui.gap(20));
        button.addActionListener(e -> onToggle.run());
        left.add(button);

        // right column: journey details + live stats
        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        Card card = new Card();
        card.setLayout(new GridLayout(0, 2, 8, 10));
        String[][] rows = {{"From", j.from()}, {"To", j.to()}, {"Distance", (int) j.distanceKm() + " km"},
            {"Est. duration", "≈ " + j.durationMin() + " min"}};
        for (String[] r : rows) {
            JLabel k = new JLabel(r[0]);
            k.setForeground(Ui.MUTED);
            k.setFont(Ui.font(Font.PLAIN, 14));
            JLabel v = new JLabel(r[1], SwingConstants.RIGHT);
            v.setForeground(Ui.TEXT);
            v.setFont(Ui.font(Font.BOLD, 15));
            card.add(k);
            card.add(v);
        }
        right.add(Ui.fixed(card, 360, 150));
        right.add(Ui.gap(12));
        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 8));
        grid.setOpaque(false);
        for (Stat s : List.of(progress, travelled, remaining, co2)) grid.add(s);
        right.add(Ui.fixed(grid, 360, 140));
        right.add(Ui.gap(12));
        right.add(bar);
        right.add(Ui.gap(12));
        right.add(toast);
        right.add(Ui.gap(12));
        Card info = new Card();
        info.setLayout(new BorderLayout());
        JLabel txt = new JLabel("<html><div style='text-align:center;width:300px'>Travelling by coach instead of car "
            + "cuts CO₂ emissions per passenger. Press Start and watch your forest grow as you save.</div></html>",
            SwingConstants.CENTER);
        txt.setFont(Ui.font(Font.PLAIN, 14));
        txt.setForeground(Ui.TEXT);
        info.add(txt);
        right.add(Ui.fixed(info, 360, 90));

        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 50, 30, 50);
        center.add(left, c);
        center.add(right, c);
        add(center, BorderLayout.CENTER);

        resetStats();
        new Timer(30, e -> {                       // animated CO2 counter + tree stage label
            shown += (target - shown) * 0.12;
            counter.setText(String.format("%.1f kg CO₂ saved", shown));
            stage.setText(Config.stage(tree.currentTreeKg()));
        }).start();
    }

    private void resetStats() {
        timer.setText("00:00");
        bar.set(0);
        progress.set("0%");
        travelled.set("0.0 km");
        remaining.set("-");
        co2.set("0.0 kg");
    }

    void setRunning(boolean running) {
        button.setText(running ? "I've got off the coach" : "Start");
        button.setColor(running ? new Color(0x8D6E63) : Ui.GREEN);
    }

    void begin(Journey j) {
        shown = target = 0;
        resetStats();
        toast.clear();
        toast.show("✅ Journey started: " + j.from() + " → " + j.to());
    }

    void finish(Snapshot s) {
        toast.show(String.format("🌳 Saved %.1f kg CO₂ - added to your forest", s.co2Kg()));
    }

    void update(Snapshot s, List<Badge> newBadges) {
        target = s.co2Kg();
        timer.setText(String.format("%02d:%02d", s.elapsedSec() / 60, s.elapsedSec() % 60));
        bar.set(s.progress());
        progress.set(String.format("%.0f%%", s.progress() * 100));
        travelled.set(String.format("%.1f km", s.distanceKm()));
        remaining.set(String.format("%.1f km", s.remainingKm()));
        co2.set(String.format("%.1f kg", s.co2Kg()));
        for (Badge b : newBadges) toast.show(b.icon() + " Badge unlocked: " + b.name());
        if (s.progress() >= 1) toast.show("📍 You've arrived in " + "Glasgow!");
    }
}
