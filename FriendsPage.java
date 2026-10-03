import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;

/** Left: your friends. Right: search every account and add people. Either list opens a profile + forest. */
final class FriendsPage extends SkyPanel {
    private static final Color ERR = new Color(0xB4443C);

    private final Accounts accounts;
    private final Supplier<String> me;
    private final Consumer<String> onView;
    private final AtomicInteger friendSeq = new AtomicInteger(), findSeq = new AtomicInteger();

    private final DefaultListModel<Accounts.Summary> friendModel = new DefaultListModel<>(), findModel = new DefaultListModel<>();
    private final JList<Accounts.Summary> friendList = JourneyPicker.styled(new JList<>(friendModel));
    private final JList<Accounts.Summary> findList = JourneyPicker.styled(new JList<>(findModel));
    private final JTextField search = JourneyPicker.field("Search by username…");
    private final JLabel status = Ui.label(" ", 13, Font.BOLD, Ui.MUTED);

    private final PillButton viewFriend = small("VIEW FOREST", Ui.TEAL), remove = small("REMOVE", Ui.INK);
    private final PillButton viewFind = small("VIEW FOREST", Ui.TEAL), add = small("ADD FRIEND", Ui.TEAL_DARK);

    FriendsPage(Accounts accounts, Consumer<String> nav, Supplier<String> me, Consumer<String> onView) {
        this.accounts = accounts;
        this.me = me;
        this.onView = onView;
        setLayout(new BorderLayout());
        add(Ui.topBar("FRIENDS", nav), BorderLayout.NORTH);

        JPanel cols = new JPanel(new GridLayout(1, 2, 28, 0));
        cols.setOpaque(false);
        cols.setBorder(BorderFactory.createEmptyBorder(8, 70, 6, 70));
        cols.add(withButtons(JourneyPicker.column("Your friends", null, friendList), viewFriend, remove));
        cols.add(withButtons(JourneyPicker.column("Find friends", search, findList), viewFind, add));
        add(cols, BorderLayout.CENTER);

        Ui.fixed(status, 700, 24);
        JPanel foot = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createEmptyBorder(0, 0, 18, 0));
        foot.add(status);
        add(foot, BorderLayout.SOUTH);

        JourneyPicker.debounce(search, this::loadFind);
        friendList.addListSelectionListener(e -> updateButtons());
        findList.addListSelectionListener(e -> updateButtons());
        doubleClick(friendList);
        doubleClick(findList);
        viewFriend.addActionListener(e -> view(friendList.getSelectedValue()));
        viewFind.addActionListener(e -> view(findList.getSelectedValue()));
        add.addActionListener(e -> {
            Accounts.Summary s = findList.getSelectedValue();
            String user = me.get();
            if (s != null && user != null) act(() -> accounts.addFriend(user, s.username()));
        });
        remove.addActionListener(e -> {
            Accounts.Summary s = friendList.getSelectedValue();
            String user = me.get();
            if (s != null && user != null) act(() -> accounts.removeFriend(user, s.username()));
        });
        updateButtons();
    }

    /** Call when the page is about to be shown. */
    void refresh() {
        loadFriends();
        loadFind();
    }

    /** Forget everything (logout) so the next user never sees the previous user's lists. */
    void reset() {
        friendSeq.incrementAndGet();
        findSeq.incrementAndGet();
        search.setText("");
        friendModel.clear();
        findModel.clear();
        setStatus(" ", false);
    }

    private void loadFriends() {
        String user = me.get();
        if (user == null) return;
        async(friendSeq, () -> accounts.friends(user), list -> fill(friendModel, list));
    }

    private void loadFind() {
        String user = me.get();
        if (user == null) return;
        String q = search.getText();
        async(findSeq, () -> accounts.search(user, q), list -> fill(findModel, list));
    }

    private void fill(DefaultListModel<Accounts.Summary> model, List<Accounts.Summary> list) {
        model.clear();
        list.forEach(model::addElement);
        updateHint();
        updateButtons();
    }

    private void updateHint() {
        if (findModel.isEmpty() && !search.getText().isBlank()) setStatus("No users match that search.", false);
        else if (findModel.isEmpty() && friendModel.isEmpty()) setStatus("There are no other accounts on this computer yet.", false);
        else if (friendModel.isEmpty()) setStatus("No friends yet - pick someone on the right and add them.", false);
        else setStatus(" ", false);
    }

    private void updateButtons() {
        Accounts.Summary f = friendList.getSelectedValue(), s = findList.getSelectedValue();
        viewFriend.setEnabled(f != null);
        remove.setEnabled(f != null);
        viewFind.setEnabled(s != null);
        add.setEnabled(s != null && !s.friend());
    }

    private void view(Accounts.Summary s) { if (s != null) onView.accept(s.username()); }

    private interface Task { void run() throws Accounts.AuthException; }

    private void act(Task t) {
        add.setEnabled(false);
        remove.setEnabled(false);
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception { t.run(); return null; }
            protected void done() {
                try { get(); refresh(); }
                catch (Exception e) { setStatus(message(e), true); updateButtons(); }
            }
        }.execute();
    }

    /* ---- helpers ---- */

    private <T> void async(AtomicInteger seq, Callable<T> job, Consumer<T> ok) {
        int mine = seq.incrementAndGet();                         // newer requests make older ones obsolete
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception { return job.call(); }
            protected void done() {
                if (mine != seq.get()) return;
                try { ok.accept(get()); }
                catch (Exception e) { setStatus(message(e), true); }
            }
        }.execute();
    }

    private static String message(Exception e) {
        Throwable c = e.getCause() != null ? e.getCause() : e;
        return c instanceof Accounts.AuthException ? c.getMessage() : "Something went wrong - try again.";
    }

    private void setStatus(String s, boolean error) {
        status.setForeground(error ? ERR : Ui.MUTED);
        status.setText(s);
    }

    private void doubleClick(JList<Accounts.Summary> list) {
        list.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) view(list.getModel().getElementAt(i));
            }
        });
    }

    private static PillButton small(String text, Color c) {
        PillButton b = new PillButton(text, c, 170);
        b.setFont(Ui.font(Font.BOLD, 14));
        b.setPreferredSize(new Dimension(170, 48));
        b.setMaximumSize(new Dimension(170, 48));
        return b;
    }

    private static JPanel withButtons(JPanel column, JButton... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
        row.setOpaque(false);
        for (JButton b : buttons) row.add(b);
        column.add(row, BorderLayout.SOUTH);
        return column;
    }
}
