import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.function.Consumer;
import javax.swing.*;

/**
 * Slide-in navigation drawer. Install it as the frame's glass pane (setGlassPane) and call open().
 * Clicking the dimmed area, pressing Esc, or choosing an item closes it.
 */
final class SideMenu extends JComponent {
    private static final int W = 300, HEADER = 170, ROW = 56, FIRST_ROW = 192;
    private static final String[][] ITEMS = {
        {"🏠", "Home", "timer"}, {"🚌", "Choose journey", "journey"}, {"🌲", "Forest", "forest"}, {"🏅", "Badges", "badges"},
        {"👤", "My profile", "profile"}, {"👥", "Friends", "friends"}};
    private static final String[] LOGOUT = {"🚪", "Log out", "logout"};

    private final Garden garden;
    private final Consumer<String> nav;
    private final Timer anim;
    private String userName;
    private double open;              // 0 = hidden, 1 = fully out
    private boolean opening;
    private int hover = -1;           // index into ITEMS, or ITEMS.length for log out
    private String pending;           // page to go to once the drawer has finished closing

    SideMenu(Garden garden, Consumer<String> nav) {
        this.garden = garden;
        this.nav = nav;
        setOpaque(false);
        setVisible(false);

        anim = new Timer(14, e -> {
            open += opening ? 0.10 : -0.10;
            if (open >= 1) { open = 1; ((Timer) e.getSource()).stop(); }
            if (open <= 0) {
                open = 0;
                ((Timer) e.getSource()).stop();
                setVisible(false);
                if (pending != null) {
                    String p = pending;
                    pending = null;
                    SwingUtilities.invokeLater(() -> nav.accept(p));
                }
            }
            repaint();
        });

        MouseAdapter mouse = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (!opening) return;
                if (e.getX() > drawerRight()) { close(null); return; }
                int i = hit(e.getPoint());
                if (i >= 0) close(i == ITEMS.length ? LOGOUT[2] : ITEMS[i][2]);
            }
            public void mouseMoved(MouseEvent e) {
                int i = e.getX() <= drawerRight() ? hit(e.getPoint()) : -1;
                if (i != hover) { hover = i; repaint(); }
                setCursor(Cursor.getPredefinedCursor(i >= 0 || e.getX() > drawerRight() ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
            public void mouseExited(MouseEvent e) { if (hover != -1) { hover = -1; repaint(); } }
        };
        addMouseListener(mouse);              // also swallows clicks so nothing underneath reacts
        addMouseMotionListener(mouse);
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getActionMap().put("close", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { close(null); }
        });
    }

    /** null = guest. */
    void setUser(String name) { userName = name; }

    void open() {
        hover = -1;
        pending = null;
        opening = true;
        setVisible(true);
        requestFocusInWindow();
        anim.start();
    }

    private void close(String thenGoTo) {
        pending = thenGoTo;
        opening = false;
        anim.start();
    }

    /* ---- geometry ---- */

    private double ease() { return 1 - Math.pow(1 - open, 3); }

    private int drawerLeft() { return (int) Math.round(-W + W * ease()); }

    private int drawerRight() { return drawerLeft() + W; }

    private Rectangle row(int i) {
        int y = i < ITEMS.length ? FIRST_ROW + i * ROW : getHeight() - 28 - ROW;
        return new Rectangle(drawerLeft() + 14, y, W - 28, ROW - 6);
    }

    private int hit(Point p) {
        for (int i = 0; i <= ITEMS.length; i++) if (row(i).contains(p)) return i;
        return -1;
    }

    /* ---- painting ---- */

    @Override protected void paintComponent(Graphics g0) {
        if (open <= 0) return;
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        int w = getWidth(), h = getHeight(), x0 = drawerLeft();

        g.setColor(new Color(40, 55, 50, (int) (110 * open)));                 // dim the page behind
        g.fillRect(0, 0, w, h);

        for (int i = 0; i < 8; i++) {                                           // soft edge shadow
            g.setColor(new Color(0, 0, 0, (int) ((9 - i) * 2 * open)));
            g.fillRect(x0 + W + i * 2, 0, 2, h);
        }
        g.setColor(Color.WHITE);
        g.fillRect(x0, 0, W, h);

        // header
        g.setPaint(new GradientPaint(0, 0, Ui.PALE, 0, HEADER, new Color(0xF2F8F6)));
        g.fillRect(x0, 0, W, HEADER);
        g.setColor(Ui.MIST);
        g.fillRect(x0, HEADER - 2, W, 2);
        g.setColor(Ui.INK);
        g.setFont(Ui.font(Font.PLAIN, 40));
        g.drawString("🌳", x0 + 26, 62);
        g.setFont(Ui.display(Font.BOLD, 25));
        g.drawString("Travel Tree", x0 + 26, 100);
        g.setFont(Ui.font(Font.BOLD, 14));
        g.setColor(Ui.TEAL_DARK);
        g.drawString(userName == null ? "Guest - progress not saved" : "👤  " + userName, x0 + 26, 125);
        g.setFont(Ui.font(Font.PLAIN, 13));
        g.setColor(Ui.MUTED);
        g.drawString(String.format("%.1f kg CO₂ saved so far", garden.total()), x0 + 26, 146);

        // menu rows
        for (int i = 0; i < ITEMS.length; i++) drawRow(g, i, ITEMS[i][0], ITEMS[i][1]);

        Rectangle lo = row(ITEMS.length);                                       // divider + log out at the bottom
        g.setColor(Ui.MIST);
        g.fillRect(x0 + 20, lo.y - 14, W - 40, 2);
        drawRow(g, ITEMS.length, LOGOUT[0], userName == null ? "Log in / sign up" : LOGOUT[1]);
        g.dispose();
    }

    private void drawRow(Graphics2D g, int i, String icon, String label) {
        Rectangle r = row(i);
        boolean on = hover == i;
        if (on) {
            g.setColor(Ui.PALE);
            g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 18, 18));
            g.setColor(Ui.TEAL);
            g.fill(new RoundRectangle2D.Double(r.x, r.y + 10, 4, r.height - 20, 4, 4));   // accent bar
        }
        int base = r.y + (r.height + 12) / 2 + 4;
        g.setFont(Ui.font(Font.PLAIN, 22));
        g.setColor(Ui.INK);
        g.drawString(icon, r.x + 18, base + 1);
        g.setFont(Ui.font(Font.BOLD, 17));
        g.setColor(on ? Ui.TEAL_DARK : Ui.INK);
        g.drawString(label, r.x + 58, base);
    }
}