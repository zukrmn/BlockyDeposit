package com.blockycraft.blockydeposit;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.*;
import java.util.logging.Logger;

import org.yaml.snakeyaml.Yaml;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import com.blockycraft.blockydeposit.lang.LanguageManager;
import com.blockycraft.blockydeposit.geoip.GeoIPManager;

public class BlockyDeposit extends JavaPlugin {

    private static final Logger LOG = Logger.getLogger("Minecraft");
    private static final SecureRandom RNG = new SecureRandom();

    private Properties cfg = new Properties();
    private File dataDir;
    private LanguageManager languageManager;
    private GeoIPManager geoIPManager;

    private File factionsDir;
    private final Yaml yaml = new Yaml();

    private String filterMode = "exclusive"; // inclusive | exclusive
    private final Set<Integer> filterIds = new HashSet<Integer>();
    private final Set<String>  filterNames = new HashSet<String>();

    @Override
    public void onEnable() {
        dataDir = getDataFolder();
        if (!dataDir.exists()) dataDir.mkdirs();

        // Initialize managers
        languageManager = new LanguageManager(this);
        geoIPManager = new GeoIPManager();

        factionsDir = new File("plugins/BlockyFactions/factions");
        if (!factionsDir.exists() || !factionsDir.isDirectory()) {
            LOG.warning("[Depositar] Diretorio das faccoes nao encontrado em: " + factionsDir.getPath());
            LOG.warning("[Depositar] A funcionalidade de deposito para fundo sera desativada.");
            factionsDir = null;
        }

        loadConfigProps();
        LOG.info("[Depositar] Ativado. Modo=" + getMode() +
                 " Filtro=" + filterMode + " Itens=" + filterIds.size() + "/" + filterNames.size());
    }

    @Override
    public void onDisable() {
        LOG.info("[Depositar] desativado.");
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public GeoIPManager getGeoIPManager() {
        return geoIPManager;
    }

    private static class FactionCheckResult {
        final boolean canDeposit;
        final String message;
        final String factionName;
        final String treasurer;
        FactionCheckResult(boolean can, String msg, String name, String tres) {
            this.canDeposit = can;
            this.message = msg;
            this.factionName = name;
            this.treasurer = tres;
        }
        static FactionCheckResult fail(String msg) {
            return new FactionCheckResult(false, msg, "", "");
        }
        static FactionCheckResult success(String name, String tres) {
            return new FactionCheckResult(true, "", name, tres);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!"deposit".equalsIgnoreCase(cmd.getName()) && !"depositar".equalsIgnoreCase(cmd.getName())) {
            return false;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Apenas jogadores podem usar esse comando.");
            return true;
        }

        final Player p = (Player) sender;
        // === CAPTURA O IDIOMA DO JOGADOR ===
        final String lang = geoIPManager.getPlayerLanguage(p);

        if (!p.hasPermission("deposit.depositar")) {
            p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.no_permission"));
            return true;
        }

        boolean isAll = false;
        boolean isHand = false;
        boolean isFund = false;
        String fundTreasurer = "";
        String fundFactionName = "";

        List<String> argsList = new ArrayList<String>(Arrays.asList(args));

        if (argsList.contains("fund") || argsList.contains("fundo")) {
            if (!p.hasPermission("deposit.depositar.fundo")) {
                p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.no_fund_perm"));
                return true;
            }
            isFund = true;
            argsList.remove("fund");
            argsList.remove("fundo");
        }

        if (argsList.contains("all") || argsList.contains("tudo")) {
            if (!p.hasPermission("deposit.depositar.tudo")) {
                p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.no_all_perm"));
                return true;
            }
            isAll = true;
            argsList.remove("all");
            argsList.remove("tudo");
        }

        if (argsList.contains("hand") || argsList.contains("mao")) {
            if (!p.hasPermission("deposit.depositar.mao")) {
                p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.no_hand_perm"));
                return true;
            }
            isHand = true;
            argsList.remove("hand");
            argsList.remove("mao");
        }

        if (!argsList.isEmpty()) {
            p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.invalid_args").replace("{cmd}", cmd.getName()));
            return true;
        }

        if (isFund) {
            if (factionsDir == null) {
                p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.no_faction_dir"));
                return true;
            }
            try {
                FactionCheckResult check = checkFactionFund(p.getName());
                if (!check.canDeposit) {
                    p.sendMessage(ChatColor.RED + check.message);
                    return true;
                }
                fundTreasurer = check.treasurer;
                fundFactionName = check.factionName; 
            } catch (Exception e) {
                p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.faction_error"));
                LOG.warning("[Depositar] Check de faccao falhou para " + p.getName() + ": " + e.getMessage());
                e.printStackTrace();
                return true;
            }
        }
                // Decide what to collect
        final CollectResult coll;
        if (isAll) {
            if (isHand) {
                ItemStack hand = p.getItemInHand();
                if (hand == null || hand.getType() == Material.AIR || hand.getAmount() <= 0) {
                    p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.hand_empty"));
                    return true;
                }
                if (!canDeposit(hand)) {
                    p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.forbidden"));
                    return true;
                }
                coll = collectByType(p, hand.getTypeId());
                if (coll.slots.length == 0) {
                    p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.no_items_matching"));
                    return true;
                }
            } else {
                coll = collectAll(p);
                if (coll.slots.length == 0) {
                    p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.no_items"));
                    return true;
                }
            }
        } else {
            coll = collectHeld(p);
            if (coll.slots.length == 0) {
                p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.hand_empty_or_forbidden"));
                return true;
            }
        }

        // Build payload
        final String username = p.getName();
        final String uuid = offlineUuid(username);
        final long timestamp = System.currentTimeMillis();
        final String hash = randomHashHex(16);

        final String json = buildDepositJson(username, uuid, timestamp, hash, coll.agg, isFund, fundTreasurer);
        final int[] slotsToClear = coll.slots;
        final int stacks = coll.totalStacks;
        final boolean finalIsFund = isFund;
        final String finalFundFactionName = fundFactionName;

        if ("http".equalsIgnoreCase(getMode())) {
            final String endpoint = cfg.getProperty("endpoint_url", "").trim();
            final int cto = intProp("connect_timeout_ms", 1500);
            final int rto = intProp("read_timeout_ms", 2000);
            if (endpoint.isEmpty()) {
                p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.no_endpoint"));
                return true;
            }

            String pendingMsg = languageManager.get(lang, "deposit.submitting");
            if (finalIsFund) {
                pendingMsg = languageManager.get(lang, "deposit.pending_fund").replace("{faction}", finalFundFactionName);
            }
            p.sendMessage(ChatColor.YELLOW + pendingMsg);

            getServer().getScheduler().scheduleAsyncDelayedTask(this, new Runnable() {
                public void run() {
                    boolean ok = postJson(endpoint, json, cto, rto);
                    if (ok) {
                        getServer().getScheduler().scheduleSyncDelayedTask(BlockyDeposit.this, new Runnable() {
                            public void run() {
                                clearSlots(p, slotsToClear);
                                String msg = languageManager.get(lang, "deposit.success")
                                    .replace("{stacks}", "" + stacks);
                                if (finalIsFund) {
                                    msg = msg + " " + languageManager.get(lang, "deposit.success_fund")
                                        .replace("{faction}", finalFundFactionName);
                                }
                                p.sendMessage(ChatColor.GREEN + msg);
                            }
                        });
                    } else {
                        p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.failed_http"));
                        LOG.warning("[Deposit] HTTP post failed for " + username);
                    }
                }
            });
        } else {
            // FILE mode
            try {
                File outDir = new File(dataDir, cfg.getProperty("output_dir", "out"));
                if (!outDir.exists()) outDir.mkdirs();
                String fname = safe(username) + "_deposit_" + timestamp + "_" + hash + ".json";
                writeUtf8(new File(outDir, fname), json);
                clearSlots(p, slotsToClear);

                String msg = languageManager.get(lang, "deposit.success_file")
                    .replace("{stacks}", "" + stacks);
                if (finalIsFund)
                    msg = msg + " " + languageManager.get(lang, "deposit.success_fund")
                        .replace("{faction}", finalFundFactionName);
                p.sendMessage(ChatColor.GREEN + msg);

            } catch (Exception e) {
                p.sendMessage(ChatColor.RED + languageManager.get(lang, "deposit.failed_file"));
                LOG.warning("[Depositar] File write failed: " + e.getMessage());
            }
        }

        return true;
    }

    // ====== filtering ======
    private boolean canDeposit(ItemStack it) {
        if (it == null) return false;
        Material m = it.getType();
        if (m == null || m == Material.AIR) return false;
        if (it.getAmount() <= 0) return false;
        try {
            if (m.getMaxDurability() > 0) return false;
        } catch (Throwable ignored) {}
        final int id = it.getTypeId();
        final String name = m.name();
        boolean listed = filterIds.contains(id) || filterNames.contains(name.toUpperCase());
        if ("inclusive".equalsIgnoreCase(filterMode))
            return listed;
        else
            return !listed;
    }

    private static class Agg {
        Map<String,Integer> qty = new HashMap<String,Integer>();
        Map<String,String>  name = new HashMap<String,String>();
    }
    private static class CollectResult {
        final Agg agg; final int[] slots; final int totalStacks;
        CollectResult(Agg a, int[] s, int n) { agg=a; slots=s; totalStacks=n; }
    }
    private CollectResult collectHeld(Player p) {
        int slot = heldSlotSafe(p);
        if (slot < 0) return new CollectResult(new Agg(), new int[0], 0);
        ItemStack it = p.getInventory().getItem(slot);
        if (!canDeposit(it)) return new CollectResult(new Agg(), new int[0], 0);
        Agg a = new Agg();
        String fullId = it.getTypeId() + ":" + it.getDurability();
        a.qty.put(fullId, it.getAmount());
        a.name.put(fullId, it.getType().name());
        return new CollectResult(a, new int[]{slot}, 1);
    }
    private CollectResult collectAll(Player p) {
        ArrayList<Integer> slots = new ArrayList<Integer>();
        Agg a = new Agg();
        ItemStack[] cont = p.getInventory().getContents();
        int stacks = 0;
        for (int i=0;i<cont.length;i++) {
            ItemStack it = cont[i];
            if (!canDeposit(it)) continue;
            stacks++;
            slots.add(i);
            String fullId = it.getTypeId() + ":" + it.getDurability();
            int amt = it.getAmount();
            a.qty.put(fullId, a.qty.containsKey(fullId) ? a.qty.get(fullId) + amt : amt);
            if (!a.name.containsKey(fullId)) a.name.put(fullId, it.getType().name());
        }
        return new CollectResult(a, toIntArray(slots), stacks);
    }
    private CollectResult collectByType(Player p, int typeId) {
        ArrayList<Integer> slots = new ArrayList<Integer>();
        Agg a = new Agg();
        ItemStack[] cont = p.getInventory().getContents();
        int stacks = 0;
        for (int i = 0; i < cont.length; i++) {
            ItemStack it = cont[i];
            if (it == null) continue;
            if (it.getType() == null || it.getType() == Material.AIR) continue;
            if (it.getTypeId() != typeId) continue;
            if (!canDeposit(it)) continue;
            stacks++;
            slots.add(i);
            String fullId = it.getTypeId() + ":" + it.getDurability();
            int amt = it.getAmount();
            a.qty.put(fullId, a.qty.containsKey(fullId) ? a.qty.get(fullId) + amt : amt);
            if (!a.name.containsKey(fullId)) a.name.put(fullId, it.getType().name());
        }
        return new CollectResult(a, toIntArray(slots), stacks);
    }
    private static int[] toIntArray(ArrayList<Integer> list) {
        int[] a = new int[list.size()];
        for (int i=0;i<list.size();i++) a[i]=list.get(i).intValue();
        return a;
    }

    private static String buildDepositJson(String username, String uuid, long ts, String hash, Agg a,
                                          boolean isFund, String treasurer) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"action\":\"deposit\",\n");
        sb.append("  \"timestamp\": ").append(ts).append(",\n");
        sb.append("  \"hash\":\"").append(jsonEscape(hash)).append("\",\n");
        sb.append("  \"username\":\"").append(jsonEscape(username)).append("\",\n");
        sb.append("  \"uuid\":\"").append(jsonEscape(uuid)).append("\",\n");
        sb.append("  \"fund\": ").append(isFund).append(",\n");
        sb.append("  \"treasurer\":\"").append(jsonEscape(treasurer)).append("\",\n");
        sb.append("  \"items\":[\n");
        boolean first = true;
        for (Map.Entry<String,Integer> e : a.qty.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            String fullId = e.getKey();
            int qty = e.getValue();
            String name = a.name.containsKey(fullId) ? a.name.get(fullId) : "";
            sb.append("    {\"id\":\"").append(jsonEscape(fullId)).append("\"")
              .append(",\"name\":\"").append(jsonEscape(name)).append("\"")
              .append(",\"quantity\":").append(qty).append("}");
        }
        sb.append("\n  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void clearSlots(Player p, int[] slots) {
        try {
            for (int s : slots) {
                if (s >= 0) p.getInventory().clear(s);
            }
        } catch (Throwable ignored) {
            for (int s : slots) {
                try { p.getInventory().setItem(s, null); } catch (Throwable __) {}
            }
        }
    }
    private static int heldSlotSafe(Player p) {
        try { return p.getInventory().getHeldItemSlot(); }
        catch (Throwable t) {
            ItemStack hand = p.getItemInHand();
            if (hand == null) return -1;
            ItemStack[] cont = p.getInventory().getContents();
            for (int i=0;i<cont.length;i++) if (cont[i] == hand) return i;
            return -1;
        }
    }

    private void loadConfigProps() {
        File f = new File(dataDir, "config.properties");
        boolean fresh = !f.exists();
        if (fresh) {
            cfg.setProperty("mode", "file");
            cfg.setProperty("output_dir", "out");
            cfg.setProperty("endpoint_url", "");
            cfg.setProperty("connect_timeout_ms", "1500");
            cfg.setProperty("read_timeout_ms", "2000");
            cfg.setProperty("filter_mode", "exclusive");
            cfg.setProperty("filter_items", "");
            try {
                FileOutputStream os = new FileOutputStream(f);
                cfg.store(os, "Deposit plugin config");
                os.close();
            } catch (Exception e) {
                LOG.warning("[Deposit] Could not write default config: " + e.getMessage());
            }
        } else {
            try {
                FileInputStream is = new FileInputStream(f);
                cfg.load(is);
                is.close();
            } catch (Exception e) {
                LOG.warning("[Deposit] Could not read config: " + e.getMessage());
            }
        }
        parseFilter();
    }
    private void parseFilter() {
        filterMode = cfg.getProperty("filter_mode", "exclusive").trim().toLowerCase();
        filterIds.clear();
        filterNames.clear();
        String raw = cfg.getProperty("filter_items", "");
        if (raw == null || raw.trim().isEmpty()) return;
        String[] parts = raw.split(",");
        for (int i=0;i<parts.length;i++) {
            String t = parts[i].trim();
            if (t.isEmpty()) continue;
            boolean allDigits = true;
            for (int k=0;k<t.length();k++) if (!Character.isDigit(t.charAt(k))) { allDigits=false; break; }
            if (allDigits) {
                try { filterIds.add(Integer.valueOf(t)); } catch (Exception ignored) {}
                continue;
            }
            filterNames.add(t.toUpperCase());
        }
    }
    private String getMode() {
        return cfg.getProperty("mode", "file").toLowerCase();
    }
    private int intProp(String key, int def) {
        try {
            String v = cfg.getProperty(key, String.valueOf(def));
            if (v == null) return def;
            v = v.trim();
            if (v.isEmpty()) return def;
            return Integer.parseInt(v);
        } catch (Exception ignored) {
            return def;
        }
    }
    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
    private static String randomHashHex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
    private static void writeUtf8(File f, String s) throws IOException {
        OutputStream out = new FileOutputStream(f);
        OutputStreamWriter w = new OutputStreamWriter(out, Charset.forName("UTF-8"));
        w.write(s);
        w.flush();
        w.close();
    }
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int k = hex.length(); k < 4; k++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
    private boolean postJson(String urlStr, String json, int connectTimeoutMs, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = conn.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.flush();
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            LOG.warning("[Deposit] HTTP error: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
    private static String offlineUuid(String name) {
        try {
            UUID u = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes("UTF-8"));
            return u.toString();
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
    private FactionCheckResult checkFactionFund(String playerName) {
        if (factionsDir == null || !factionsDir.isDirectory()) {
            return FactionCheckResult.fail("Faction data not loaded.");
        }
        File[] files = factionsDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".yml");
            }
        });
        if (files == null) {
            return FactionCheckResult.fail("Could not read faction directory.");
        }
        for (File file : files) {
            try {
                FileInputStream is = new FileInputStream(file);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) yaml.load(is);
                is.close();

                if (data == null) continue;

                String factionName = (String) data.get("nome");
                if (factionName == null || factionName.isEmpty()) factionName = file.getName().replace(".yml","");

                boolean isMember = false;
                Object lider = data.get("lider");
                if (lider != null && lider.toString().equalsIgnoreCase(playerName)) {
                    isMember = true;
                }
                if (!isMember && data.containsKey("oficiais")) {
                    @SuppressWarnings("unchecked")
                    List<String> oficiais = (List<String>) data.get("oficiais");
                    if (oficiais != null) {
                        for (String oficial : oficiais) {
                            if (oficial.equalsIgnoreCase(playerName)) {
                                isMember = true;
                                break;
                            }
                        }
                    }
                }
                if (!isMember && data.containsKey("membros")) {
                    @SuppressWarnings("unchecked")
                    List<String> membros = (List<String>) data.get("membros");
                    if (membros != null) {
                        for (String membro : membros) {
                            if (membro.equalsIgnoreCase(playerName)) {
                                isMember = true;
                                break;
                            }
                        }
                    }
                }
                if (isMember) {
                    Object tesoureiroObj = data.get("tesoureiro");
                    String tesoureiro = (tesoureiroObj == null) ? "" : tesoureiroObj.toString().trim();
                    if (tesoureiro.isEmpty()) {
                        return FactionCheckResult.fail("Nao ha tesoureiro definido para essa faccao " + factionName);
                    }
                    return FactionCheckResult.success(factionName, tesoureiro);
                }
            } catch (Exception e) {
                LOG.warning("[Depositar] Falha ao processar o arquivo da faccao: " + file.getName() + ": " + e.getMessage());
            }
        }
        return FactionCheckResult.fail("Voce nao faz parte de nenhuma faccao, nao eh possivel depositar no fundo da faccao.");
    }
}

