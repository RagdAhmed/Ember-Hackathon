import java.awt.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Page 2 of the sketch: isometric forest with a back arrow. Takes on the colours of the current map. */
final class ForestPage extends SkyPanel {
    private final Garden garden;
    private final JPanel claim = new JPanel();
    private final JLabel next = Ui.label(" ", 15, Font.BOLD, Ui.INK);

    ForestPage(Garden garden, Consumer<String> nav, Runnable onNewMap) {
        super(true);
        this.garden = garden;
        setLayout(new BorderLayout());
        add(Ui.topBar(null, nav), BorderLayout.NORTH);
        add(new ForestCanvas(garden), BorderLayout.CENTER);

        // shown only when the forest is full: unlock the next map
        claim.setOpaque(false);
        claim.setLayout(new BoxLayout(claim, BoxLayout.Y_AXIS));
        claim.setBorder(BorderFactory.createEmptyBorder(2, 0, 20, 0));
        PillButton newMap = new PillButton("START NEW MAP", Ui.TEAL, 340);
        newMap.addActionListener(e -> onNewMap.run());
        claim.add(next);
        claim.add(Ui.gap(8));
        claim.add(newMap);
        claim.setVisible(false);
        add(claim, BorderLayout.SOUTH);
        refresh();
    }

    /** Call whenever the page is about to be shown or the map changes. */
    void refresh() {
        ForestMap m = garden.map();
        setTheme(m.skyTop(), m.skyBottom());
        boolean full = garden.forestFull();
        if (full) {
            ForestMap n = garden.nextMapPreview();
            next.setText("Next up: " + n.icon() + " " + n.name());
        }
        claim.setVisible(full);
        revalidate();
        repaint();
    }
}