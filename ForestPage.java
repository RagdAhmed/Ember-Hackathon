import java.awt.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Page 2 of the sketch: isometric forest with a back arrow. */
final class ForestPage extends SkyPanel {
    ForestPage(Garden garden, Consumer<String> nav) {
        super(true);
        setLayout(new BorderLayout());
        add(Ui.topBar(null, nav), BorderLayout.NORTH);
        add(new ForestCanvas(garden), BorderLayout.CENTER);
    }
}