package id.roleplay.uistudio;

import org.bukkit.configuration.file.YamlConfiguration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

/** Daftar gambar dari folder images/. Tiap gambar mendapat 1 karakter unik di font UIStudio. */
public class Images {
    public static class Img {
        public String id;
        public char ch;
        public int w, h, height, ascent, advance;
        public File file;
    }

    private final UIStudio plugin;
    private final Map<String, Img> map = new LinkedHashMap<>();
    private final Set<String> warned = new HashSet<>();

    public Images(UIStudio plugin) { this.plugin = plugin; }

    public Collection<Img> all() { return map.values(); }

    public Img get(String id) {
        if (id == null) return null;
        Img i = map.get(id.toLowerCase());
        if (i == null && warned.add(id)) plugin.getLogger().warning("Gambar '" + id + "' tidak ada di folder images/.");
        return i;
    }

    public static String sanitize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    public void load() {
        map.clear();
        warned.clear();
        File dir = new File(plugin.getDataFolder(), "images");
        File yml = new File(plugin.getDataFolder(), "images.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(yml);
        int next = y.getInt("next-char", 0xE000);

        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (files == null) files = new File[0];
        Arrays.sort(files);

        for (File f : files) {
            String id = sanitize(f.getName().substring(0, f.getName().length() - 4));
            BufferedImage bi;
            try {
                bi = ImageIO.read(f);
            } catch (IOException e) {
                bi = null;
            }
            if (bi == null) {
                plugin.getLogger().warning("Gambar rusak / bukan PNG: " + f.getName());
                continue;
            }
            String base = "images." + id;
            int ch = y.contains(base + ".char") ? y.getInt(base + ".char") : next++;
            int height = Math.max(1, y.getInt(base + ".height", bi.getHeight()));
            int defAscent = id.startsWith("gui_") ? 13 : height / 2 + 3;
            int ascent = Math.max(0, Math.min(height, y.getInt(base + ".ascent", defAscent)));
            y.set(base + ".char", ch);
            y.set(base + ".height", height);
            y.set(base + ".ascent", ascent);

            Img img = new Img();
            img.id = id;
            img.ch = (char) ch;
            img.w = bi.getWidth();
            img.h = bi.getHeight();
            img.height = height;
            img.ascent = ascent;
            img.advance = (int) Math.round(bi.getWidth() * (double) height / bi.getHeight()) + 1;
            img.file = f;
            map.put(id, img);
        }
        y.set("next-char", next);
        try {
            y.save(yml);
        } catch (IOException e) {
            plugin.getLogger().warning("Gagal menyimpan images.yml: " + e.getMessage());
        }
        plugin.getLogger().info(map.size() + " gambar terdaftar.");
    }
}
