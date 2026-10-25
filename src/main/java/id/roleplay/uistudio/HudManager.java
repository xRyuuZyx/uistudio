package id.roleplay.uistudio;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;

/** HUD: baris boss bar + action bar berisi gambar, teks, bar, animasi. */
public class HudManager implements Listener {
    public static class Def {
        public final String id;
        public final File file;
        public final YamlConfiguration cfg;

        Def(String id, File file, YamlConfiguration cfg) {
            this.id = id;
            this.file = file;
            this.cfg = cfg;
        }
    }

    private static final String[] NO_ARGS = new String[0];
    private final UIStudio plugin;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> active = new HashMap<>();
    private final Map<UUID, List<BossBar>> bars = new HashMap<>();
    private BukkitTask task;

    public HudManager(UIStudio plugin) { this.plugin = plugin; }

    public Set<String> ids() { return defs.keySet(); }

    public Def get(String id) { return defs.get(id.toLowerCase()); }

    public void load() {
        defs.clear();
        File dir = new File(plugin.getDataFolder(), "huds");
        if (!dir.exists()) {
            dir.mkdirs();
            plugin.saveResource("huds/main.yml", false);
            plugin.saveResource("huds/demo.yml", false);
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4).toLowerCase();
                defs.put(id, new Def(id, f, YamlConfiguration.loadConfiguration(f)));
            }
        }
        plugin.getLogger().info(defs.size() + " HUD dimuat.");
    }

    public void start() {
        if (task != null) task.cancel();
        long interval = Math.max(2L, plugin.getConfig().getLong("hud.interval-ticks", 5));
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) render(p);
        }, 20L, interval);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) clear(p);
    }

    // ---------------- API ----------------

    public boolean show(Player p, String id) {
        if (!defs.containsKey(id.toLowerCase())) return false;
        active.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashSet<>()).add(id.toLowerCase());
        return true;
    }

    public void hide(Player p, String id) {
        Set<String> s = active.get(p.getUniqueId());
        if (s != null) s.remove(id.toLowerCase());
        if (s == null || s.isEmpty()) clear(p);
    }

    public void toggle(Player p, String id) {
        Set<String> s = active.get(p.getUniqueId());
        if (s != null && s.contains(id.toLowerCase())) hide(p, id); else show(p, id);
    }

    public Set<String> activeOf(Player p) { return active.getOrDefault(p.getUniqueId(), Set.of()); }

    private void clear(Player p) {
        List<BossBar> list = bars.remove(p.getUniqueId());
        if (list != null) for (BossBar b : list) p.hideBossBar(b);
        p.sendActionBar(Component.empty());
    }

    // ---------------- Render ----------------

    private void render(Player p) {
        Set<String> ids = active.get(p.getUniqueId());
        if (ids == null || ids.isEmpty()) return;

        List<Component> boss = new ArrayList<>();
        Component action = null;
        for (String hid : ids) {
            Def d = defs.get(hid);
            if (d == null) continue;
            int span = d.cfg.getInt("span", 240);
            for (Map<?, ?> row : d.cfg.getMapList("rows")) {
                Component c = buildRow(p, row, span);
                String type = row.get("type") == null ? "bossbar" : String.valueOf(row.get("type")).toLowerCase();
                if (type.equals("actionbar")) action = c; else boss.add(c);
            }
        }

        List<BossBar> list = bars.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        while (list.size() > boss.size()) p.hideBossBar(list.remove(list.size() - 1));
        for (int i = 0; i < boss.size(); i++) {
            if (i < list.size()) {
                list.get(i).name(boss.get(i));
            } else {
                BossBar b = BossBar.bossBar(boss.get(i), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
                p.showBossBar(b);
                list.add(b);
            }
        }
        if (action != null) p.sendActionBar(action);
    }

    private static int num(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }

    private static TextColor color(Object o) {
        if (o == null) return null;
        return TextColor.fromHexString(String.valueOf(o));
    }

    private double parse(Player p, Object expr, double def) {
        if (expr == null) return def;
        String s = plugin.placeholders().apply(p, String.valueOf(expr), NO_ARGS).replaceAll("[^0-9.\\-]", "");
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Component buildRow(Player p, Map<?, ?> row, int span) {
        Row r = new Row(plugin.images(), -span);
        Object layers = row.get("layers");
        if (layers instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> l)) continue;
                String type = String.valueOf(l.get("type")).toLowerCase();
                int x = num(l.get("x"), 0);
                switch (type) {
                    case "image" -> r.image(String.valueOf(l.get("id")), color(l.get("color")), x);
                    case "text" -> r.text(plugin.placeholders().apply(p, String.valueOf(l.get("text")), NO_ARGS), x);
                    case "animation" -> {
                        if (l.get("frames") instanceof List<?> fr && !fr.isEmpty()) {
                            int fps = Math.max(1, num(l.get("fps"), 4));
                            int idx = (int) ((System.currentTimeMillis() * fps / 1000) % fr.size());
                            r.image(String.valueOf(fr.get(idx)), color(l.get("color")), x);
                        }
                    }
                    case "bar" -> {
                        String img = l.get("image") == null ? "seg" : String.valueOf(l.get("image"));
                        int n = Math.max(1, num(l.get("segments"), 10));
                        double max = Math.max(0.0001, parse(p, l.get("max"), 20));
                        double val = parse(p, l.get("value"), 0);
                        int filled = (int) Math.round(Math.max(0, Math.min(1, val / max)) * n);
                        if (val > 0 && filled == 0) filled = 1;
                        TextColor on = color(l.get("color"));
                        TextColor off = color(l.get("empty"));
                        if (on == null) on = NamedTextColor.WHITE;
                        if (off == null) off = TextColor.color(0x33333d);
                        r.moveTo(x);
                        for (int i = 0; i < n; i++) r.image(img, i < filled ? on : off, r.pos());
                    }
                    default -> { }
                }
            }
        }
        return r.finish(span);
    }

    // ---------------- Event ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        List<String> auto = plugin.getConfig().getStringList("hud.auto");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            for (String id : auto) show(p, id);
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        active.remove(e.getPlayer().getUniqueId());
        bars.remove(e.getPlayer().getUniqueId());
    }
}
