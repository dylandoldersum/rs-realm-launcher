package rs.realm.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The signed-in session, remembered between launches.
 *
 * <h2>What is stored, and what deliberately is not</h2>
 *
 * Only the backend-issued session token, plus the Discord name and avatar URL so the window can draw
 * itself before the first network call returns. The Discord access and refresh tokens are never here
 * — the backend throws them away the moment it learns who you are, so there is nothing to keep.
 *
 * <p>The token is written in plain text, and calling that "encrypted storage" would be theatre: any
 * key the launcher could use to decrypt it would have to sit next to the file, readable by exactly
 * the processes that can read the file. What actually limits the damage is that the token is
 * revocable and expires — signing out on one machine cannot be undone by whoever copied the file,
 * and thirty days is the longest it is worth anything.
 *
 * <p>On POSIX systems the file is created 0600 so other users on a shared machine cannot read it.
 */
public final class Session {

    private static final String KEY_TOKEN = "session";
    private static final String KEY_USERNAME = "discord.username";
    private static final String KEY_AVATAR = "discord.avatar";
    private static final String KEY_PROFILE = "profile";

    private final Path file;
    private final Properties props = new Properties();

    public Session(Path file) {
        this.file = file;
        load();
    }

    public String token() {
        return trimmed(props.getProperty(KEY_TOKEN));
    }

    public String username() {
        return trimmed(props.getProperty(KEY_USERNAME));
    }

    public String avatarUrl() {
        return trimmed(props.getProperty(KEY_AVATAR));
    }

    /** The profile last played, so the dropdown reopens where the player left it. */
    public String lastProfile() {
        return trimmed(props.getProperty(KEY_PROFILE));
    }

    public void save(String token, Backend.Account account) {
        props.setProperty(KEY_TOKEN, token == null ? "" : token);
        props.setProperty(KEY_USERNAME, account == null ? "" : nullToEmpty(account.username()));
        props.setProperty(KEY_AVATAR, account == null ? "" : nullToEmpty(account.avatarUrl()));
        store();
    }

    public void saveLastProfile(String loginUsername) {
        props.setProperty(KEY_PROFILE, nullToEmpty(loginUsername));
        store();
    }

    /** Forgets everything about the signed-in account, including on disk. */
    public void clear() {
        props.clear();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // The player asked to sign out; an undeletable file must not leave them still signed in,
            // so the in-memory clear above is what counts and this is only tidying.
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            // A corrupt or unreadable file is the same as not being signed in — the player signs in
            // again and it gets overwritten.
        }
    }

    private void store() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "RS-Realm launcher session — delete this file to sign out.");
            }
            restrictPermissions();
        } catch (IOException e) {
            // Not fatal: the player stays signed in for this run and is asked again next time.
        }
    }

    private void restrictPermissions() {
        try {
            var view = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Windows has no POSIX view; the per-user profile directory is the protection there.
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
