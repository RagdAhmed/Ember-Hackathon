import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.Timer;

/** Page 2 of the sketch: isometric forest. */
final class ForestPage extends SkyPanel {
    ForestPage(Garden garden, Consumer<String> nav) {
        setLayout(new BorderLayout());
        add(Ui.topBar("forest", nav), BorderLayout.NORTH);
        add(new ForestCanvas(garden), BorderLayout.CENTER);
    }
}
