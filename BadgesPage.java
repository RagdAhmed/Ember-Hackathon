import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.Timer;

/** Page 1 of the sketch: 3 x 2 grid of badges. */
final class BadgesPage extends SkyPanel {
    private final Garden garden;
    private final Map<Badge, BadgeTile> tiles = new LinkedHashMap<>();

    BadgesPage(Garden garden, Consumer<String> nav) {
        this.garden = garden;
        setLayout(new BorderLayout());
        add(Ui.topBar("badges", nav), BorderLayout.NORTH);
        JPanel grid = new JPanel(new GridLayout(2, 3, 30, 10));
        grid.setOpaque(false);
        grid.setBorder(BorderFactory.createEmptyBorder(10, 80, 30, 80));
        for (Badge b : Config.BADGES) {
            BadgeTile t = new BadgeTile(b);
            tiles.put(b, t);
            grid.add(t);
        }
        add(grid, BorderLayout.CENTER);
    }

    void refresh() { tiles.forEach((b, t) -> t.setUnlocked(garden.unlocked.contains(b))); }
}

