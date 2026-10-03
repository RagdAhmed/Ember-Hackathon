import java.awt.*;
import java.util.function.Consumer;
import javax.swing.*;

/** First screen: log in, create an account, or carry on as a guest (progress not saved). */
final class LoginPage extends SkyPanel {
    private final Accounts accounts;
    private final Consumer<Accounts.Profile> onLogin;
    private boolean registering;

    private final JLabel loginTab = tab("LOG IN"), signupTab = tab("SIGN UP");
    private final JTextField user = field(new JTextField());
    private final JPasswordField pass = field(new JPasswordField());
    private final JPasswordField confirm = field(new JPasswordField());
    private final JPanel confirmRow = left(small("Confirm password"));
    private final JLabel error = Ui.label(" ", 13, Font.BOLD, new Color(0xB4443C));
    private final PillButton submit = new PillButton("LOG IN", Ui.TEAL, 340);

    LoginPage(Accounts accounts, Consumer<Accounts.Profile> onLogin, Runnable onGuest) {
        this.accounts = accounts;
        this.onLogin = onLogin;
        setLayout(new GridBagLayout());

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        JLabel logo = Ui.label("🌳", 54, Font.PLAIN, Ui.INK);
        JLabel title = new JLabel("Coach CO₂ Tracker", SwingConstants.CENTER);
        title.setFont(Ui.display(Font.BOLD, 38));
        title.setForeground(Ui.INK);
        title.setAlignmentX(0.5f);
        JLabel sub = Ui.label("Sign in to keep your forest and badges.", 14, Font.PLAIN, Ui.MUTED);

        JPanel tabs = new JPanel(new GridLayout(1, 2, 10, 0));
        tabs.setOpaque(false);
        tabs.add(loginTab);
        tabs.add(signupTab);
        Ui.fixed(tabs, 340, 34);
        loginTab.addMouseListener(click(() -> setMode(false)));
        signupTab.addMouseListener(click(() -> setMode(true)));

        col.add(logo);
        col.add(title);
        col.add(Ui.gap(2));
        col.add(sub);
        col.add(Ui.gap(22));
        col.add(tabs);
        col.add(Ui.gap(14));
        col.add(left(small("Username")));
        col.add(Ui.gap(4));
        col.add(Ui.fixed(user, 340, 42));
        col.add(Ui.gap(10));
        col.add(left(small("Password")));
        col.add(Ui.gap(4));
        col.add(Ui.fixed(pass, 340, 42));
        col.add(Ui.gap(10));
        col.add(confirmRow);
        col.add(Ui.gap(4));
        col.add(Ui.fixed(confirm, 340, 42));
        col.add(Ui.gap(8));
        col.add(Ui.fixed(error, 420, 20));
        col.add(Ui.gap(8));
        col.add(submit);

        JButton guest = new JButton("Continue as guest (progress won't be saved)");
        guest.setFont(Ui.font(Font.PLAIN, 13));
        guest.setForeground(Ui.MUTED);
        Ui.plainButton(guest, 340, 30);
        guest.setAlignmentX(0.5f);
        guest.setMaximumSize(new Dimension(340, 30));
        guest.addActionListener(e -> { clear(); onGuest.run(); });
        col.add(Ui.gap(10));
        col.add(guest);

        add(col);

        submit.addActionListener(e -> submit());
        user.addActionListener(e -> pass.requestFocusInWindow());
        pass.addActionListener(e -> { if (registering) confirm.requestFocusInWindow(); else submit(); });
        confirm.addActionListener(e -> submit());
        setMode(false);
    }

    /** Call when the page is (re)shown, e.g. after logging out. */
    void reset() {
        clear();
        setMode(false);
        user.requestFocusInWindow();
    }

    private void setMode(boolean register) {
        registering = register;
        style(loginTab, !register);
        style(signupTab, register);
        confirm.setVisible(register);
        confirmRow.setVisible(register);
        confirm.setText("");
        error.setText(" ");
        submit.setText(register ? "CREATE ACCOUNT" : "LOG IN");
        revalidate();
        repaint();
    }

    private void submit() {
        submit.setEnabled(false);
        error.setForeground(Ui.MUTED);
        error.setText(registering ? "Creating account…" : "Logging in…");
        String name = user.getText();
        char[] pw = pass.getPassword(), cf = confirm.getPassword();
        boolean reg = registering;
        // PBKDF2 is deliberately slow, so keep it off the UI thread.
        new SwingWorker<Accounts.Profile, Void>() {
            protected Accounts.Profile doInBackground() throws Exception {
                return reg ? accounts.register(name, pw, cf) : accounts.login(name, pw);
            }
            protected void done() {
                submit.setEnabled(true);
                try {
                    Accounts.Profile p = get();
                    clear();
                    onLogin.accept(p);
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    error.setForeground(new Color(0xB4443C));
                    error.setText(c instanceof Accounts.AuthException ? c.getMessage() : "Something went wrong - try again.");
                    pass.setText("");
                    confirm.setText("");
                }
            }
        }.execute();
    }

    private void clear() {
        pass.setText("");
        confirm.setText("");
        error.setText(" ");
    }

    /* ---- small UI helpers ---- */

    private static JLabel tab(String text) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setFont(Ui.font(Font.BOLD, 14));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setOpaque(true);
        return l;
    }

    private static void style(JLabel tab, boolean active) {
        tab.setBackground(active ? Ui.SOFT : Ui.PALE);
        tab.setForeground(active ? Ui.INK : Ui.MUTED);
    }

    private static JLabel small(String t) {
        JLabel l = new JLabel(t);
        l.setFont(Ui.font(Font.BOLD, 13));
        l.setForeground(Ui.TEAL_DARK);
        return l;
    }

    /** Left-aligns a label inside the centred column, matching the 340px field width. */
    private static JPanel left(JLabel l) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(l, BorderLayout.WEST);
        Ui.fixed(p, 340, 18);
        return p;
    }

    private static <T extends JTextField> T field(T f) {
        f.setFont(Ui.font(Font.PLAIN, 15));
        f.setForeground(Ui.INK);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.MIST, 2, true),
            BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        return f;
    }

    private static java.awt.event.MouseAdapter click(Runnable r) {
        return new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) { r.run(); }
        };
    }
}
