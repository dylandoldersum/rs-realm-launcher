package rs.realm.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The client look the player picks (Look: OSRS / 634 HD), kept in {@code ~/.rs-realm/look.txt}
 * (not in the session file: signing out keeps it). The 634 HD look starts the client with
 * {@code -Drsrealm.look=634}: the server sends that client the 634 look's cache (models,
 * animations, the 634 ground and Lumbridge), the client keeps it in a cache folder of its own, and
 * 117 HD presets its 634 look. OSRS passes nothing: the OSRS cache, and 117 HD as set in its own
 * settings. Both play in the same world.
 */
final class ClientLook {
    static final String OSRS = "OSRS";
    static final String HD_634 = "634 HD";
    static final List<String> CHOICES = List.of(OSRS, HD_634);

    private static final Path FILE = Config.INSTALL_DIR.resolve("look.txt");

    private ClientLook() {}

    /** The look picked last ({@link #OSRS} when none). */
    static String load() {
        try {
            if (Files.isRegularFile(FILE)) {
                String v = Files.readString(FILE).trim();
                if (v.equals("634")) {
                    return HD_634;
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable: the default
        }
        return OSRS;
    }

    static void save(String look) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, HD_634.equals(look) ? "634\n" : "osrs\n");
        } catch (IOException e) {
            // not kept: the next start offers the default again
        }
    }

    /** The client's {@code -Drsrealm.look} value for the look picked, or null for none (OSRS). */
    static String property() {
        return HD_634.equals(load()) ? "634" : null;
    }
}
