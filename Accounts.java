import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;
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