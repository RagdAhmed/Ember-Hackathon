import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Local accounts: one small file per user in  ~/.coachco2/users/<name>.properties
 * (override the folder with -Dcoach.data=/some/dir).
 * Passwords are never stored - only a salted PBKDF2-HMAC-SHA256 hash.
 */
final class Accounts {
    static final int MIN_PASSWORD = 8;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,20}");
    private static final int ITERATIONS = 150_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What gets saved on a profile. */
    record Profile(String username, double bankedKg, int journeys, int mapIndex) {}

    static final int MAX_BIO = 140;

    /** One row in the friends / search lists. */
    record Summary(String username, double bankedKg, int mapIndex, boolean friend) {
        int trees() { return (int) (bankedKg / Config.FULL_TREE_KG); }
        @Override public String toString() {
            return username + "  ·  " + String.format("%.1f kg", bankedKg) + "  ·  " + trees()
                + (trees() == 1 ? " tree" : " trees") + (friend ? "  ·  ✓ friend" : "");
        }
    }

    /** Everything another user is allowed to see (never the password hash or salt). */
    record PublicProfile(String username, double bankedKg, int journeys, int mapIndex, String bio, String joined,
                         int friendCount, boolean friend, boolean self) {}

    /** Problem the user can fix (bad password, name taken...). The message is safe to show. */
    static final class AuthException extends Exception {
        AuthException(String msg) { super(msg); }
    }

    private final Path dir;

    Accounts() {
        this(Paths.get(System.getProperty("coach.data", System.getProperty("user.home") + File.separator + ".coachco2"),
            "users"));
    }

    Accounts(Path dir) { this.dir = dir; }

    Profile register(String username, char[] password, char[] confirm) throws AuthException {
        try {
            username = username == null ? "" : username.trim();
            if (!NAME.matcher(username).matches())
                throw new AuthException("Username must be 3-20 letters, numbers or underscores.");
            if (password.length < MIN_PASSWORD)
                throw new AuthException("Password must be at least " + MIN_PASSWORD + " characters.");
            if (!Arrays.equals(password, confirm)) throw new AuthException("Passwords don't match.");
            Path file = fileFor(username);
            if (Files.exists(file)) throw new AuthException("That username is already taken.");

            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            Properties p = new Properties();
            p.setProperty("username", username);
            p.setProperty("salt", b64(salt));
            p.setProperty("iterations", String.valueOf(ITERATIONS));
            p.setProperty("hash", b64(hash(password, salt, ITERATIONS)));
            p.setProperty("bankedKg", "0");
            p.setProperty("journeys", "0");
            p.setProperty("map", "0");
            p.setProperty("joined", LocalDate.now().toString());
            write(file, p);
            return new Profile(username, 0, 0, 0);
        } catch (IOException e) {
            throw new AuthException("Couldn't save your account on this computer (" + e.getMessage() + ").");
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirm, '\0');
        }
    }

    Profile login(String username, char[] password) throws AuthException {
        try {
            username = username == null ? "" : username.trim();
            // One message for "no such user" and "wrong password" so usernames can't be probed.
            AuthException bad = new AuthException("Incorrect username or password.");
            if (!NAME.matcher(username).matches()) throw bad;
            Path file = fileFor(username);
            if (!Files.exists(file)) throw bad;
            Properties p = read(file);
            byte[] salt = Base64.getDecoder().decode(p.getProperty("salt", ""));
            byte[] want = Base64.getDecoder().decode(p.getProperty("hash", ""));
            int iters = Integer.parseInt(p.getProperty("iterations", String.valueOf(ITERATIONS)));
            if (!MessageDigest.isEqual(want, hash(password, salt, iters))) throw bad;
            return new Profile(p.getProperty("username", username),
                Math.max(0, Double.parseDouble(p.getProperty("bankedKg", "0"))),
                Math.max(0, Integer.parseInt(p.getProperty("journeys", "0"))),
                Math.max(0, Integer.parseInt(p.getProperty("map", "0"))));
        } catch (IOException | RuntimeException e) {
            if (e instanceof AuthException) throw (AuthException) e;
            throw new AuthException("Couldn't read that account's file - it may be damaged.");
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Save progress, keeping the stored password hash untouched. */
    void save(Profile profile) throws IOException {
        Path file = fileFor(profile.username());
        Properties p = read(file);
        p.setProperty("bankedKg", String.valueOf(profile.bankedKg()));
        p.setProperty("journeys", String.valueOf(profile.journeys()));
        p.setProperty("map", String.valueOf(profile.mapIndex()));
        write(file, p);
    }

    /* ---- profile + friends ---- */

    /** Another user's public profile (or your own: self = true). */
    PublicProfile view(String me, String username) throws AuthException {
        try {
            Path file = existingFile(username);
            Properties p = read(file);
            Set<String> mine = friendKeys(me);
            String name = p.getProperty("username", username);
            boolean self = key(name).equals(key(me));
            return new PublicProfile(name, num(p, "bankedKg"), (int) num(p, "journeys"), (int) num(p, "map"),
                cleanBio(p.getProperty("bio", "")), p.getProperty("joined", ""),
                splitFriends(p.getProperty("friends", "")).size(), mine.contains(key(name)), self);
        } catch (IOException | RuntimeException e) {
            if (e instanceof AuthException) throw (AuthException) e;
            throw new AuthException("Couldn't read that profile.");
        }
    }

    /** Your friends, A-Z. Friends whose account has since vanished are skipped. */
    List<Summary> friends(String me) throws AuthException {
        try {
            List<Summary> out = new ArrayList<>();
            for (String k : friendKeys(me)) {
                Path f = dir.resolve(k + ".properties");
                if (Files.exists(f)) out.add(summary(read(f), k, true));
            }
            out.sort(Comparator.comparing(x -> x.username().toLowerCase(Locale.ROOT)));
            return out;
        } catch (IOException | RuntimeException e) {
            throw new AuthException("Couldn't load your friends list.");
        }
    }

    /** Other users whose name contains the query (blank = everyone), A-Z, at most 30. Never includes you. */
    List<Summary> search(String me, String query) throws AuthException {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String mine = key(me);
        List<Summary> out = new ArrayList<>();
        try {
            if (!Files.isDirectory(dir)) return out;
            Set<String> friends = friendKeys(me);
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) { files = s.filter(x -> x.toString().endsWith(".properties")).toList(); }
            for (Path f : files) {
                String k = f.getFileName().toString().replaceFirst("\\.properties$", "");
                if (k.equals(mine) || !k.contains(q)) continue;
                try { out.add(summary(read(f), k, friends.contains(k))); } catch (RuntimeException | IOException skip) {}
            }
        } catch (IOException e) {
            throw new AuthException("Couldn't search for users.");
        }
        out.sort(Comparator.comparing((Summary x) -> !x.username().toLowerCase(Locale.ROOT).startsWith(q))
            .thenComparing(x -> x.username().toLowerCase(Locale.ROOT)));
        return out.size() > 30 ? out.subList(0, 30) : out;
    }

    void addFriend(String me, String other) throws AuthException {
        if (key(me).equals(key(other))) throw new AuthException("You can't add yourself.");
        edit(me, p -> {
            existingFile(other);                                  // must be a real account
            Set<String> set = new LinkedHashSet<>(splitFriends(p.getProperty("friends", "")));
            set.add(key(other));
            p.setProperty("friends", String.join(",", set));
        });
    }

    void removeFriend(String me, String other) throws AuthException {
        edit(me, p -> {
            Set<String> set = new LinkedHashSet<>(splitFriends(p.getProperty("friends", "")));
            set.remove(key(other));
            p.setProperty("friends", String.join(",", set));
        });
    }

    void setBio(String me, String bio) throws AuthException {
        String clean = cleanBio(bio);
        edit(me, p -> p.setProperty("bio", clean));
    }

    /** Plain text only, one line, capped - it is shown to other users. */
    static String cleanBio(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\p{Cntrl}", " ").trim();
        return s.length() > MAX_BIO ? s.substring(0, MAX_BIO) : s;
    }

    private interface Change { void apply(Properties p) throws AuthException; }

    private void edit(String me, Change c) throws AuthException {
        try {
            Path file = existingFile(me);
            Properties p = read(file);
            c.apply(p);
            write(file, p);
        } catch (IOException e) {
            throw new AuthException("Couldn't save that change (" + e.getMessage() + ").");
        }
    }

    private Path existingFile(String username) throws AuthException {
        if (username == null || !NAME.matcher(username.trim()).matches()) throw new AuthException("No such user.");
        Path f = fileFor(username.trim());
        if (!Files.exists(f)) throw new AuthException("No such user.");
        return f;
    }

    private Set<String> friendKeys(String me) throws IOException {
        if (me == null || !NAME.matcher(me).matches()) return Set.of();
        Path f = fileFor(me);
        return Files.exists(f) ? new LinkedHashSet<>(splitFriends(read(f).getProperty("friends", ""))) : Set.of();
    }

    private static List<String> splitFriends(String csv) {
        List<String> out = new ArrayList<>();
        for (String x : csv.split(","))
            if (NAME.matcher(x.trim()).matches()) out.add(x.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static Summary summary(Properties p, String fallback, boolean friend) {
        return new Summary(p.getProperty("username", fallback), num(p, "bankedKg"), (int) num(p, "map"), friend);
    }

    private static double num(Properties p, String k) {
        try { double d = Double.parseDouble(p.getProperty(k, "0")); return Double.isFinite(d) ? Math.max(0, d) : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    private static String key(String name) { return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); }

    /* ---- internals ---- */

    private Path fileFor(String username) {
        return dir.resolve(username.toLowerCase(Locale.ROOT) + ".properties");   // NAME regex blocks "../" tricks
    }

    private static byte[] hash(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    private static Properties read(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) { p.load(r); }
        return p;
    }

    /** Write to a temp file then move it into place, so a crash can't leave half a profile. */
    private void write(Path file, Properties p) throws IOException {
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "save", ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp)) { p.store(w, "Coach CO2 profile"); }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        try {   // best effort: owner-only on POSIX systems
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {}
    }
}