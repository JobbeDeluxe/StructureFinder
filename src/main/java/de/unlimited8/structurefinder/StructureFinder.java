package de.unlimited8.structurefinder;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.generator.structure.Structure;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StructureFinder extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, SearchJob> running = new HashMap<>();
    private final Map<String, List<Location>> completed = new HashMap<>();
    private final Map<UUID, Menu> menus = new HashMap<>();
    private final Map<String, String> types = new LinkedHashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        // Registry keys are resolved at runtime, so custom/datapack structures also work via commands.
        types.put("village_plains", "Dorf (Ebene)");
        types.put("village_desert", "Dorf (Wüste)");
        types.put("village_savanna", "Dorf (Savanne)");
        types.put("village_snowy", "Dorf (Schnee)");
        types.put("village_taiga", "Dorf (Taiga)");
        types.put("mansion", "Waldanwesen");
        types.put("ancient_city", "Antike Stadt");
        types.put("trial_chambers", "Prüfungskammer");
        types.put("stronghold", "Festung");
        types.put("desert_pyramid", "Wüstentempel");
        types.put("jungle_pyramid", "Dschungeltempel");
        types.put("ocean_monument", "Ozeanmonument");
        types.put("pillager_outpost", "Plünderer-Außenposten");
        types.put("shipwreck", "Schiffswrack");
        types.put("buried_treasure", "Vergrabener Schatz");
        types.put("mineshaft", "Mine");
        types.put("ruined_portal", "Zerstörtes Portal");
        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("structures");
        if (command != null) { command.setExecutor(this); command.setTabCompleter(this); }
        getLogger().info("StructureFinder aktiviert (GUI + RCON). Suche ist angenaehert, nicht garantiert top-10.");
    }

    @Override public void onDisable() {
        for (SearchJob job : running.values()) if (job.task != null) job.task.cancel();
        running.clear(); menus.clear();
    }

    private String key(UUID id, String structure) { return id + ":" + structure; }
    private String norm(String s) {
        s = s.toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }
    private Structure lookup(String id) {
        NamespacedKey key = NamespacedKey.fromString(norm(id));
        return key == null ? null : Registry.STRUCTURE.get(key);
    }
    private void reply(CommandSender sender, String message) { sender.sendMessage(ChatColor.AQUA + "[Structures] " + ChatColor.RESET + message); }
    private boolean permitted(CommandSender sender) { return !(sender instanceof Player) || sender.hasPermission("structurefinder.use"); }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!permitted(sender)) { reply(sender, "Keine Berechtigung (structurefinder.use)."); return true; }
        if (args.length == 0) {
            if (sender instanceof Player p) showPlayers(p);
            else help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender);
            case "players" -> reply(sender, "Online auf diesem Paper-Backend: " + String.join(", ", Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()));
            case "types" -> {
                reply(sender, "Struktur-IDs: " + String.join(", ", types.keySet()));
                reply(sender, "Auch Namespace-IDs aus dem Struktur-Registry möglich.");
            }
            case "find" -> {
                if (args.length < 3) { reply(sender, "Nutzung: structures find <spieler> <struktur> [anzahl]"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { reply(sender, "Spieler nicht auf diesem Paper-Backend online: " + args[1]); return true; }
                if (!canTarget(sender, target)) return true;
                String id = norm(args[2]);
                Structure structure = lookup(id);
                if (structure == null) { reply(sender, "Struktur unbekannt: " + id + ". Siehe 'structures types'."); return true; }
                int count = getConfig().getInt("search.default-results", 10);
                if (args.length > 3) {
                    try { count = Integer.parseInt(args[3]); } catch (NumberFormatException ex) { reply(sender, "Anzahl muss eine Zahl sein."); return true; }
                }
                count = Math.max(1, Math.min(getConfig().getInt("search.max-results", 10), count));
                startSearch(sender, target, id, structure, count);
            }
            case "results" -> {
                if (args.length < 3) { reply(sender, "Nutzung: structures results <spieler> <struktur>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { reply(sender, "Spieler nicht online auf diesem Backend."); return true; }
                if (!canTarget(sender, target)) return true;
                printResults(sender, target, norm(args[2]));
            }
            case "tp" -> {
                if (args.length < 4) { reply(sender, "Nutzung: structures tp <spieler> <struktur> <index> [self|target]"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { reply(sender, "Zielspieler nicht online auf diesem Backend."); return true; }
                if (!canTarget(sender, target)) return true;
                final int index;
                try { index = Integer.parseInt(args[3]) - 1; } catch (NumberFormatException ex) { reply(sender, "Index muss eine Zahl sein."); return true; }
                boolean self = args.length > 4 && args[4].equalsIgnoreCase("self");
                Player traveler = self && sender instanceof Player p ? p : target;
                if (self && !(sender instanceof Player)) { reply(sender, "RCON kann 'self' nicht nutzen. Verwende 'target'."); return true; }
                List<Location> locations = completed.get(key(target.getUniqueId(), norm(args[2])));
                if (locations == null || index < 0 || index >= locations.size()) { reply(sender, "Kein solches Ergebnis. Erst find, danach results."); return true; }
                teleport(sender, traveler, locations.get(index));
            }
            case "cancel" -> {
                if (args.length < 3) { reply(sender, "Nutzung: structures cancel <spieler> <struktur>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null || !canTarget(sender, target)) return true;
                SearchJob job = running.remove(key(target.getUniqueId(), norm(args[2])));
                if (job != null) { job.task.cancel(); reply(sender, "Suche abgebrochen."); }
                else reply(sender, "Keine laufende Suche.");
            }
            default -> help(sender);
        }
        return true;
    }

    private boolean canTarget(CommandSender sender, Player target) {
        if (sender instanceof Player p && !p.getUniqueId().equals(target.getUniqueId()) && !p.hasPermission("structurefinder.admin")) {
            reply(sender, "Für andere Spieler benötigst du structurefinder.admin."); return false;
        }
        return true;
    }
    private void help(CommandSender sender) {
        reply(sender, "structures | players | types");
        reply(sender, "structures find <spieler> <struktur> [1-10]");
        reply(sender, "structures results <spieler> <struktur>");
        reply(sender, "structures tp <spieler> <struktur> <index> [self|target]");
        reply(sender, "structures cancel <spieler> <struktur>");
    }

    private void startSearch(CommandSender sender, Player target, String id, Structure structure, int requested) {
        String cacheKey = key(target.getUniqueId(), id);
        if (running.containsKey(cacheKey)) { reply(sender, "Suche läuft bereits. 'structures results " + target.getName() + " " + id + "' für Status."); return; }
        World world = target.getWorld();
        Location center = target.getLocation().clone();
        int radius = Math.max(1, getConfig().getInt("search.radius-chunks", 96));
        int probes = Math.max(1, Math.min(100, getConfig().getInt("search.max-probes", 36)));
        long interval = Math.max(1L, getConfig().getLong("search.ticks-between-probes", 10));
        int spacing = Math.max(128, getConfig().getInt("search.probe-spacing-blocks", 1200));
        SearchJob job = new SearchJob(cacheKey, sender, target.getUniqueId(), target.getName(), id, world, center, structure, requested, probes, radius, spacing);
        running.put(cacheKey, job);
        completed.remove(cacheKey);
        // Vanilla locateNearestStructure muss synchron ausgeführt werden. Pro Durchgang genau ein Lookup.
        job.task = Bukkit.getScheduler().runTaskTimer(this, () -> probe(job), 1L, interval);
        reply(sender, "Suche gestartet um " + target.getName() + " in " + world.getName() + " nach " + id + " (max. " + probes + " Suchpunkte). Ergebnisse per 'structures results " + target.getName() + " " + id + "'.");
    }

    private void probe(SearchJob job) {
        if (job.next >= job.maxProbes) { finish(job); return; }
        int n = job.next++;
        // Quadratische Spirale auf einem Suchpunktraster: start (0,0), dann weiter nach außen.
        int[] p = spiral(n);
        Location sample = job.center.clone().add(p[0] * job.spacing, 0, p[1] * job.spacing);
        try {
            var result = job.world.locateNearestStructure(sample, job.structure, job.radiusChunks, false);
            if (result != null) {
                Location found = result.getLocation();
                String unique = found.getBlockX() + ":" + found.getBlockZ();
                if (job.seen.add(unique)) job.found.add(found.clone());
            }
        } catch (Throwable ex) {
            getLogger().warning("Struktursuche fehlgeschlagen fuer " + job.id + ": " + ex.getMessage());
            reply(job.requester, "Suche fehlgeschlagen: " + ex.getClass().getSimpleName() + ". Details in Konsole.");
            job.task.cancel(); running.remove(job.key); return;
        }
        // Eine Top-10-Garantie gibt es mit dem Vanilla-Locator nicht; wir sammeln bewusst weiter.
        if (job.next >= job.maxProbes) finish(job);
    }

    private static int[] spiral(int n) {
        if (n == 0) return new int[]{0, 0};
        int ring = (int) Math.ceil((Math.sqrt(n + 1.0) - 1.0) / 2.0);
        int side = ring * 2;
        int highest = (2 * ring + 1) * (2 * ring + 1) - 1;
        int d = highest - n;
        if (d < side) return new int[]{ring - d, -ring};
        if ((d -= side) < side) return new int[]{-ring, -ring + d};
        if ((d -= side) < side) return new int[]{-ring + d, ring};
        d -= side;
        return new int[]{ring, ring - d};
    }

    private void finish(SearchJob job) {
        job.task.cancel(); running.remove(job.key);
        job.found.sort(Comparator.comparingDouble(l -> dist2(job.center, l)));
        List<Location> result = List.copyOf(job.found.subList(0, Math.min(job.requested, job.found.size())));
        completed.put(job.key, result);
        reply(job.requester, "Suche abgeschlossen: " + result.size() + " verschiedene Fundorte für " + job.targetName + ". 'structures results " + job.targetName + " " + job.id + "'");
        if (job.requester instanceof Player player && player.isOnline()) showResults(player, job.targetName, job.id);
    }

    private double dist2(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
    private void printResults(CommandSender sender, Player target, String id) {
        SearchJob job = running.get(key(target.getUniqueId(), id));
        if (job != null) { reply(sender, "Suche läuft: " + job.next + "/" + job.maxProbes + " Suchpunkte, " + job.found.size() + " eindeutige Treffer. Erneut abfragen."); return; }
        List<Location> list = completed.get(key(target.getUniqueId(), id));
        if (list == null) { reply(sender, "Kein fertiger Suchlauf. Erst: structures find " + target.getName() + " " + id); return; }
        reply(sender, "Ergebnisse für " + target.getName() + " / " + id + " (" + list.size() + "; nur angenäherte nächste Fundorte):");
        for (int i = 0; i < list.size(); i++) {
            Location l = list.get(i);
            reply(sender, (i + 1) + ". Welt=" + l.getWorld().getName() + " X=" + l.getBlockX() + " Y=" + l.getBlockY() + " Z=" + l.getBlockZ() + " (" + Math.round(Math.sqrt(dist2(target.getLocation(), l))) + " Blöcke aktuell)");
        }
    }

    private void teleport(CommandSender sender, Player traveler, Location found) {
        // Sicherer Spawnpunkt in einem bereits existierenden Chunk; keine Teleports unter Wasser/Lava.
        World world = found.getWorld();
        int x = found.getBlockX(), z = found.getBlockZ();
        if (world == null || !world.getWorldBorder().isInside(found)) { reply(sender, "Fundort liegt außerhalb der Weltgrenze."); return; }
        // Chunkload geschieht auf Server-Hauptthread, erst danach wird die Standhöhe bestimmt.
        world.getChunkAt(x >> 4, z >> 4);
        int y = world.getHighestBlockYAt(x, z) + 1;
        if (y < world.getMinHeight() || y + 1 >= world.getMaxHeight()) { reply(sender, "Kein sicherer Zielbereich."); return; }
        Location destination = new Location(world, x + .5, y, z + .5, traveler.getLocation().getYaw(), traveler.getLocation().getPitch());
        // Minimale Sicherheitsprüfung; beispielsweise Überhänge und Flüssigkeiten werden abgelehnt.
        if (!destination.getBlock().isPassable() || !destination.clone().add(0, 1, 0).getBlock().isPassable()
                || destination.clone().add(0, -1, 0).getBlock().isLiquid()) {
            reply(sender, "Kein sicherer Oberflächenpunkt an den Koordinaten; teleportiere nicht."); return;
        }
        traveler.teleportAsync(destination).thenAccept(success -> Bukkit.getScheduler().runTask(this,
            () -> reply(sender, success ? "Teleportiert: " + traveler.getName() + " nach " + world.getName() + " " + x + " " + y + " " + z
                    : "Teleport fehlgeschlagen.")));
    }

    private void showPlayers(Player viewer) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        Inventory inv = Bukkit.createInventory(null, 54, "Structures: Spieler");
        Map<Integer, String> actions = new HashMap<>();
        int i = 0;
        for (Player target : players) {
            if (i >= 54) break;
            if (!viewer.getUniqueId().equals(target.getUniqueId()) && !viewer.hasPermission("structurefinder.admin")) continue;
            inv.setItem(i, item(Material.PLAYER_HEAD, target.getName(), "Strukturen in der Nähe anzeigen"));
            actions.put(i++, target.getName());
        }
        menus.put(viewer.getUniqueId(), new Menu("players", "", actions));
        viewer.openInventory(inv);
    }
    private void showTypes(Player viewer, String target) {
        Inventory inv = Bukkit.createInventory(null, 54, "Structures: Typ wählen");
        Map<Integer, String> actions = new HashMap<>();
        int i = 0;
        for (var e : types.entrySet()) {
            if (i >= 54) break;
            inv.setItem(i, item(Material.MAP, e.getValue(), e.getKey()));
            actions.put(i++, e.getKey());
        }
        menus.put(viewer.getUniqueId(), new Menu("types", target, actions));
        viewer.openInventory(inv);
    }
    private void showResults(Player viewer, String targetName, String id) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) return;
        List<Location> list = completed.get(key(target.getUniqueId(), id));
        if (list == null) return;
        Inventory inv = Bukkit.createInventory(null, 27, "Structures: Fundorte");
        Map<Integer, String> actions = new HashMap<>();
        for (int i = 0; i < list.size() && i < 10; i++) {
            Location l = list.get(i);
            inv.setItem(i, item(Material.COMPASS, "Fundort #" + (i + 1),
                "X " + l.getBlockX() + " Y " + l.getBlockY() + " Z " + l.getBlockZ(),
                "Linksklick: selbst teleportieren", "Rechtsklick: Zielspieler teleportieren"));
            actions.put(i, String.valueOf(i + 1));
        }
        menus.put(viewer.getUniqueId(), new Menu("results", targetName + "|" + id, actions));
        viewer.openInventory(inv);
    }
    private ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + name);
        meta.setLore(java.util.Arrays.stream(lore).map(s -> ChatColor.GRAY + s).toList());
        item.setItemMeta(meta);
        return item;
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Menu menu = menus.get(player.getUniqueId());
        if (menu == null || !event.getView().getTitle().startsWith("Structures:")) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        String value = menu.actions.get(event.getRawSlot());
        if (value == null) return;
        boolean rightClick = event.isRightClick();
        Bukkit.getScheduler().runTask(this, () -> {
            switch (menu.kind) {
                case "players" -> showTypes(player, value);
                case "types" -> {
                    player.closeInventory();
                    onCommand(player, getCommand("structures"), "structures", new String[]{"find", menu.context, value, "10"});
                }
                case "results" -> {
                    player.closeInventory();
                    String[] parts = menu.context.split("\\|", 2);
                    onCommand(player, getCommand("structures"), "structures", new String[]{"tp", parts[0], parts[1], value, rightClick ? "target" : "self"});
                }
            }
        });
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!permitted(sender)) return List.of();
        if (args.length == 1) return filter(List.of("help", "players", "types", "find", "results", "tp", "cancel"), args[0]);
        if (args.length == 2 && List.of("find", "results", "tp", "cancel").contains(args[0].toLowerCase(Locale.ROOT)))
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        if (args.length == 3 && List.of("find", "results", "tp", "cancel").contains(args[0].toLowerCase(Locale.ROOT)))
            return filter(new ArrayList<>(types.keySet()), args[2]);
        if (args.length == 5 && args[0].equalsIgnoreCase("tp")) return filter(List.of("self", "target"), args[4]);
        return List.of();
    }
    private List<String> filter(List<String> options, String typed) { return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT))).toList(); }
    private record Menu(String kind, String context, Map<Integer, String> actions) {}
    private static final class SearchJob {
        final String key, targetName, id;
        final CommandSender requester;
        final UUID targetId;
        final World world;
        final Location center;
        final Structure structure;
        final int requested, maxProbes, radiusChunks, spacing;
        final Set<String> seen = new HashSet<>();
        final List<Location> found = new ArrayList<>();
        int next;
        BukkitTask task;
        SearchJob(String key, CommandSender requester, UUID targetId, String targetName, String id,
                  World world, Location center, Structure structure, int requested, int maxProbes, int radiusChunks, int spacing) {
            this.key = key; this.requester = requester; this.targetId = targetId;
            this.targetName = targetName; this.id = id; this.world = world; this.center = center;
            this.structure = structure; this.requested = requested; this.maxProbes = maxProbes;
            this.radiusChunks = radiusChunks; this.spacing = spacing;
        }
    }
}
