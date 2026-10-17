package rs.realm.launcher;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * The pictures the game client draws for the launcher (its CharacterTurntablePlugin), on this
 * machine: each character all the way round in {@code ~/.rs-realm/renders/<name>/}, and each store
 * item the launcher asked for in {@code ~/.rs-realm/items/<id>/} (asked for in
 * {@code items/wanted.txt}). A set is a folder of numbered PNGs and an info.json, swapped in whole.
 */
public final class Renders {
    private static final File RENDERS = new File(Config.INSTALL_DIR.toFile(), "renders");
    private static final File ITEMS = new File(Config.INSTALL_DIR.toFile(), "items");
    /** Store pictures downloaded from the server, for a player who has not drawn their own. */
    private static final File DEFAULTS = new File(Config.INSTALL_DIR.toFile(), "defaults");
    private static volatile Backend backend;
    /** Defaults asked of the server this run (each once). */
    private static final java.util.Set<String> checked = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern TIME = Pattern.compile("\"time\"\\s*:\\s*(\\d+)");
    private static final Pattern FRAMES = Pattern.compile("\"frames\"\\s*:\\s*(\\d+)");

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "renders-loader");
        t.setDaemon(true);
        return t;
    });

    /** Uploads (an admin's), apart from the loading so they never hold a picture up. */
    private static final ExecutorService PUBLISHER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "renders-publisher");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** The id under which an admin's latest character is published ("hero"): the shape of the silhouettes. */
    private static final int HERO = 0;

    private Renders() {}

    /** A set of frames on disk. */
    public record Set(File dir, String name, long time, int frames, String info) {
        /** Each frame's length in client ticks (20 ms), for an animation; null for a turn. */
        public int[] delays() {
            Matcher m = DELAYS.matcher(info);
            if (!m.find()) {
                return null;
            }
            String[] parts = m.group(1).split(",");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
            }
            return out;
        }

        /** The equipment slot it is worn in ("Head"), or "". */
        public String slot() {
            Matcher m = SLOT.matcher(info);
            return m.find() ? m.group(1) : "";
        }
    }

    private static final Pattern DELAYS = Pattern.compile("\"delays\"\\s*:\\s*\\[([0-9,\\s]*)\\]");
    private static final Pattern SLOT = Pattern.compile("\"slot\"\\s*:\\s*\"([^\"]*)\"");

    /** The name without a title or tags ("<col=be2ed6>Alpha</col> Fiddle" is "Fiddle"). */
    public static String clean(String name) {
        return name.replaceAll("<col=[^>]*>.*?</col>", "").replaceAll("<[^>]*>", "").replace(' ', ' ').trim();
    }

    /** Every character's latest render, by cleaned lower-case name. Reads the disk: not on the UI thread. */
    public static Map<String, Set> characters() {
        Map<String, Set> out = new HashMap<>();
        File[] dirs = RENDERS.listFiles(File::isDirectory);
        if (dirs == null) {
            return out;
        }
        for (File dir : dirs) {
            Set set = read(dir);
            if (set == null) {
                continue;
            }
            String key = clean(set.name()).toLowerCase(Locale.ROOT);
            Set had = out.get(key);
            if (had == null || had.time() < set.time()) {
                out.put(key, set);
            }
        }
        return out;
    }

    /** The render of store item [objId], or null (not drawn yet). Reads the disk. */
    public static Set item(int objId) {
        return read(new File(ITEMS, Integer.toString(objId)));
    }

    /** [objId] worn by [character] (turning), or null. Reads the disk. */
    public static Set worn(int objId, String character) {
        return read(new File(new File(ITEMS, Integer.toString(objId)), "worn-" + safe(character)));
    }

    /** [character] playing [objId]'s animation, or null. Reads the disk. */
    public static Set use(int objId, String character) {
        return read(new File(new File(ITEMS, Integer.toString(objId)), "use-" + safe(character)));
    }

    /** Any character's worn render of [objId] (the newest), for when the chosen one has none. */
    public static Set anyWorn(int objId) {
        return newest(new File(ITEMS, Integer.toString(objId)), "worn-");
    }

    public static Set anyUse(int objId) {
        return newest(new File(ITEMS, Integer.toString(objId)), "use-");
    }

    private static Set newest(File dir, String prefix) {
        File[] dirs = dir.listFiles(f -> f.isDirectory() && f.getName().startsWith(prefix));
        Set best = null;
        if (dirs != null) {
            for (File d : dirs) {
                Set set = read(d);
                if (set != null && (best == null || set.time() > best.time())) {
                    best = set;
                }
            }
        }
        return best;
    }

    private static String safe(String name) {
        return clean(name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    /** The server to fetch default pictures from (and, for an admin, publish to). */
    public static void useBackend(Backend b) {
        backend = b;
    }

    /** Item [objId] turning: this player's own picture, else the server's default. Reads the disk / network. */
    public static Set itemOrDefault(int objId) {
        Set custom = custom(objId, "item");
        if (custom != null) return custom;
        Set own = item(objId);
        return own != null ? own : fetched(objId, "item");
    }

    /** [character] wearing [objId]; else any of this player's characters; else the server's default. */
    public static Set wornOrDefault(int objId, String character) {
        Set custom = custom(objId, "worn");
        if (custom != null) return custom;
        Set own = character == null || character.isEmpty() ? null : worn(objId, character);
        if (own == null) own = anyWorn(objId);
        return own != null ? own : fetched(objId, "worn");
    }

    public static Set useOrDefault(int objId, String character) {
        Set custom = custom(objId, "use");
        if (custom != null) return custom;
        Set own = character == null || character.isEmpty() ? null : use(objId, character);
        if (own == null) own = anyUse(objId);
        return own != null ? own : fetched(objId, "use");
    }

    /** The server's picture of [objId] when an admin uploaded it (it wins over this player's own), else null. */
    private static Set custom(int objId, String kind) {
        Set server = fetched(objId, kind);
        return server != null && server.info().contains("\"custom\":true") ? server : null;
    }

    /** Asks the server again for [objId]'s pictures (an admin just changed one). */
    public static void refetch(int objId) {
        checked.removeIf(k -> k.startsWith(objId + "/"));
    }

    /** The server's default [kind] picture of [objId], downloaded (or refreshed) once a run. */
    private static Set fetched(int objId, String kind) {
        File dir = new File(new File(DEFAULTS, Integer.toString(objId)), kind);
        Backend b = backend;
        if (b != null && checked.add(objId + "/" + kind)) {
            try {
                File marker = new File(dir, "server-time");
                long since = marker.isFile() ? Long.parseLong(Files.readString(marker.toPath()).trim()) : 0;
                Backend.RenderZip zip = b.downloadRender(objId, kind, since);
                if (zip != null) {
                    unzip(zip.zip(), dir);
                    Files.writeString(marker.toPath(), Long.toString(zip.time()));
                }
            } catch (Exception ignored) {
                // keep what there is
            }
        }
        return read(dir);
    }

    /** Unpacks a picture zip into [dir], built aside and swapped in. Only frames and info.json. */
    private static void unzip(byte[] zip, File dir) throws java.io.IOException {
        File tmp = new File(dir.getParentFile(), dir.getName() + ".tmp");
        deleteAll(tmp);
        Files.createDirectories(tmp.toPath());
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("info.json") || name.matches("\\d{2,3}\\.png")) {
                    Files.write(new File(tmp, name).toPath(), in.readAllBytes());
                }
            }
        }
        deleteAll(dir);
        if (!tmp.renameTo(dir)) {
            deleteAll(tmp);
        }
    }

    private static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteAll(k);
        }
        f.delete();
    }

    /**
     * An admin's launcher: publishes this machine's pictures of [objIds] (the item, and worn / used by
     * the latest character) as everyone's default, those new since they were last published.
     */
    public static void publish(List<Integer> objIds) {
        Backend b = backend;
        if (b == null) {
            return;
        }
        PUBLISHER.submit(() -> {
            publish(b, HERO, "hero", newestCharacter());
            for (int id : objIds) {
                publish(b, id, "item", item(id));
                publish(b, id, "worn", anyWorn(id));
                publish(b, id, "use", anyUse(id));
            }
        });
    }

    private static Set newestCharacter() {
        Set best = null;
        for (Set set : characters().values()) {
            if (best == null || set.time() > best.time()) best = set;
        }
        return best;
    }

    /** Somebody's character, for the shape of a silhouette: this machine's latest, else the server's. */
    public static Set anyCharacterOrDefault() {
        Set own = newestCharacter();
        return own != null ? own : fetched(HERO, "hero");
    }

    private static void publish(Backend b, int objId, String kind, Set set) {
        if (set == null) {
            return;
        }
        File marker = new File(new File(ITEMS, Integer.toString(objId)), "published-" + kind);
        try {
            long published = marker.isFile() ? Long.parseLong(Files.readString(marker.toPath()).trim()) : 0;
            if (published >= set.time()) {
                return;
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(bytes)) {
                File[] files = set.dir().listFiles();
                if (files == null) return;
                for (File f : files) {
                    if (f.getName().equals("info.json") || f.getName().matches("\\d{2,3}\\.png")) {
                        out.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
                        out.write(Files.readAllBytes(f.toPath()));
                        out.closeEntry();
                    }
                }
            }
            b.uploadRender(objId, kind, bytes.toByteArray());
            Files.createDirectories(marker.getParentFile().toPath());
            Files.writeString(marker.toPath(), Long.toString(set.time()));
        } catch (Exception ignored) {
            // tried again next time
        }
    }

    /** Asks the game client to draw these items (the next time it runs): id, and the animation for "Use" (-1). */
    public static void want(Map<Integer, Integer> objIds) {
        want(objIds, Map.of());
    }

    /** As [want], with a render recipe for the cosmetics (by picture id; the game client draws them). */
    public static void want(Map<Integer, Integer> objIds, Map<Integer, String> recipes) {
        LOADER.submit(() -> {
            try {
                Files.createDirectories(ITEMS.toPath());
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<Integer, Integer> e : objIds.entrySet()) {
                    sb.append(e.getKey()).append(' ').append(e.getValue());
                    String recipe = recipes.get(e.getKey());
                    if (recipe != null && !recipe.isEmpty()) {
                        sb.append(' ').append(recipe);
                    }
                    sb.append('\n');
                }
                Files.writeString(new File(ITEMS, "wanted.txt").toPath(), sb.toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // cosmetic
            }
        });
    }

    private static Set read(File dir) {
        if (dir == null || dir.getName().endsWith(".tmp")) {
            return null;
        }
        try {
            String info = Files.readString(new File(dir, "info.json").toPath(), StandardCharsets.UTF_8);
            Matcher name = NAME.matcher(info), time = TIME.matcher(info), count = FRAMES.matcher(info);
            if (name.find() && time.find() && count.find()) {
                return new Set(dir, name.group(1), Long.parseLong(time.group(1)), Integer.parseInt(count.group(1)), info);
            }
        } catch (Exception ignored) {
            // not a (complete) set
        }
        return null;
    }

    /**
     * Loads [set]'s frames scaled to [height] pixels (smoothly, once, so drawing them is a plain copy)
     * and hands them to [done] on the UI thread. Nothing is handed over if a frame is missing.
     */
    public static void load(Set set, int height, Consumer<BufferedImage[]> done) {
        LOADER.submit(() -> {
            BufferedImage[] frames = new BufferedImage[set.frames()];
            try {
                for (int i = 0; i < frames.length; i++) {
                    BufferedImage raw = ImageIO.read(new File(set.dir(), String.format("%02d.png", i)));
                    if (raw == null) {
                        return;
                    }
                    frames[i] = scale(raw, height);
                }
            } catch (Exception e) {
                return;
            }
            SwingUtilities.invokeLater(() -> done.accept(frames));
        });
    }

    private static BufferedImage scale(BufferedImage src, int height) {
        if (height <= 0 || height == src.getHeight()) {
            return src;
        }
        int width = Math.max(1, Math.round(src.getWidth() * height / (float) src.getHeight()));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return out;
    }
}
