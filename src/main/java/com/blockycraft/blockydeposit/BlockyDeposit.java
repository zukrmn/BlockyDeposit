package com.blockycraft.blockydeposit;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.*;
import java.util.logging.Logger;

// --- NEW ---
// Bukkit includes SnakeYAML, so no extra dependencies are needed.
import org.yaml.snakeyaml.Yaml;
// --- END NEW ---

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class BlockyDeposit extends JavaPlugin {

    private static final Logger LOG = Logger.getLogger("Minecraft");
    private static final SecureRandom RNG = new SecureRandom();

    private Properties cfg = new Properties();
    private File dataDir;

    // --- NEW ---
    private File factionsDir;
    private final Yaml yaml = new Yaml();
    // --- END NEW ---

    // parsed filter
    private String filterMode = "exclusive"; // inclusive | exclusive
    private final Set<Integer> filterIds = new HashSet<Integer>();
    private final Set<String>  filterNames = new HashSet<String>(); // uppercased enum names

    @Override
    public void onEnable() {
        dataDir = getDataFolder();
        if (!dataDir.exists()) dataDir.mkdirs();
        
        // --- NEW ---
        // Locate the BlockyFactions directory
        factionsDir = new File("plugins/BlockyFactions/factions");
        if (!factionsDir.exists() || !factionsDir.isDirectory()) {
            LOG.warning("[Depositar] Diretorio das faccoes nao encontrado em: " + factionsDir.getPath());
            LOG.warning("[Depositar] A funcionalidade de deposito para fundo sera desativada.");
            factionsDir = null; // Disable fund feature
        }
        // --- END NEW ---

        loadConfigProps();
        LOG.info("[Depositar] Ativado. Modo=" + getMode() +
                 " Filtro=" + filterMode + " Itens=" + filterIds.size() + "/" + filterNames.size());
    }

    @Override
    public void onDisable() {
        LOG.info("[Depositar] desativado.");
    }

    // --- NEW ---
    /**
     * Helper class to return multiple values from the faction check.
     */
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
    // --- END NEW ---

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // --- MODIFIED ---
        // Listen for both English and Portuguese commands
        if (!"deposit".equalsIgnoreCase(cmd.getName()) && !"depositar".equalsIgnoreCase(cmd.getName())) {
            return false;
        }
        // --- END MODIFIED ---

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Apenas jogadores podem usar esse comando.");
            return true;
        }

        final Player p = (Player) sender;

        // --- NEW ---
        // Base permission check
        if (!p.hasPermission("deposit.depositar")) {
            p.sendMessage(ChatColor.RED + "Voce nao possui permissoes para usar esse comando.");
            return true;
        }

        // Flexible argument parsing
        boolean isAll = false;
        boolean isHand = false;
        boolean isFund = false;
        String fundTreasurer = "";
        String fundFactionName = "";

        // Use a List for easier parsing
        List<String> argsList = new ArrayList<String>(Arrays.asList(args));

        // Check for 'fund'/'fundo' and remove it
        if (argsList.contains("fund") || argsList.contains("fundo")) {
            if (!p.hasPermission("deposit.depositar.fundo")) {
                 p.sendMessage(ChatColor.RED + "Voce nao tem permissao para usar o comando de fundo.");
                 return true;
            }
            isFund = true;
            argsList.remove("fund");
            argsList.remove("fundo");
        }
        
        // Check for 'all'/'tudo' and remove it
        if (argsList.contains("all") || argsList.contains("tudo")) {
            if (!p.hasPermission("deposit.depositar.tudo")) {
                 p.sendMessage(ChatColor.RED + "Voce nao tem permissao para depositar todos os itens.");
                 return true;
            }
            isAll = true;
            argsList.remove("all");
            argsList.remove("tudo");
        }

        // Check for 'hand'/'mao' and remove it
        if (argsList.contains("hand") || argsList.contains("mao")) {
             if (!p.hasPermission("deposit.depositar.mao")) {
                 p.sendMessage(ChatColor.RED + "Voce nao tem permissao para depositar itens iguais a mao.");
                 return true;
            }
            isHand = true;
            argsList.remove("hand");
            argsList.remove("mao");
        }
        
        // Any remaining args are invalid
        if (!argsList.isEmpty()) {
            p.sendMessage(ChatColor.RED + "Argumentos invalidos. Uso: /" + cmd.getName() + " [all|tudo] [hand|mao] [fund|fundo]");
            return true;
        }

        // Faction fund logic check
        if (isFund) {
            if (factionsDir == null) {
                p.sendMessage(ChatColor.RED + "Diretorio das faccoes nao encontrado, deposito para faccao desativado.");
                return true;
            }
            try {
                FactionCheckResult check = checkFactionFund(p.getName());
                if (!check.canDeposit) {
                    p.sendMessage(ChatColor.RED + check.message);
                    return true;
                }
                // Success! Store the treasurer and faction name
                fundTreasurer = check.treasurer;
                fundFactionName = check.factionName; 

            } catch (Exception e) {
                p.sendMessage(ChatColor.RED + "Erro ao checar dados da faccao, tente novamente mais tarde.");
                LOG.warning("[Depositar] Check de faccao falhou para " + p.getName() + ": " + e.getMessage());
                e.printStackTrace(); // Good for debugging
                return true;
            }
        }
        // --- END NEW ---


        // Decide what to collect
        final CollectResult coll;
        // --- MODIFIED ---
        if (isAll) {
            if (isHand) {
        // --- END MODIFIED ---
                ItemStack hand = p.getItemInHand();
                if (hand == null || hand.getType() == Material.AIR || hand.getAmount() <= 0) {
                    p.sendMessage(ChatColor.RED + "Sua mao esta vazia.");
                    return true;
                }
                if (!canDeposit(hand)) {
                    p.sendMessage(ChatColor.RED + "Esse item nao pode ser depositado.");
                    return true;
                }
                coll = collectByType(p, hand.getTypeId());
                if (coll.slots.length == 0) {
                    p.sendMessage(ChatColor.RED + "Nenhum item correspondente elegivel para deposito.");
                    return true;
                }
            } else {
                coll = collectAll(p);
                if (coll.slots.length == 0) {
                    p.sendMessage(ChatColor.RED + "Nenhum item elegivel para deposito.");
                    return true;
                }
            }
        } else {
            coll = collectHeld(p);
            if (coll.slots.length == 0) {
                p.sendMessage(ChatColor.RED + "Sua mao esta vazia ou esse item nao pode ser depositado.");
                return true;
            }
        }

        // Build payload
        final String username = p.getName();
        final String uuid = offlineUuid(username);
        final long timestamp = System.currentTimeMillis();
        final String hash = randomHashHex(16); // 32 hex chars
        
        // --- MODIFIED ---
        // Pass fund status and treasurer to JSON builder
        final String json = buildDepositJson(username, uuid, timestamp, hash, coll.agg, isFund, fundTreasurer);
        // --- END MODIFIED ---

        final int[] slotsToClear = coll.slots;
        final int stacks = coll.totalStacks;
        
        // --- NEW ---
        // Final variable for use in anonymous inner class
        final boolean finalIsFund = isFund;
        final String finalFundFactionName = fundFactionName;
        // --- END NEW ---

        if ("http".equalsIgnoreCase(getMode())) {
            final String endpoint = cfg.getProperty("endpoint_url", "").trim();
            final int cto = intProp("connect_timeout_ms", 1500);
            final int rto = intProp("read_timeout_ms", 2000);
            if (endpoint.isEmpty()) {
                p.sendMessage(ChatColor.RED + "Deposit endpoint not set. Ask an admin.");
                return true;
            }
            
            // --- MODIFIED ---
            String pendingMsg = "Submitting deposit...";
            if (finalIsFund) {
                pendingMsg = ChatColor.YELLOW + "Depositing to " + finalFundFactionName + " fund...";
            }
            p.sendMessage(ChatColor.YELLOW + pendingMsg);
            // --- END MODIFIED ---
            
            getServer().getScheduler().scheduleAsyncDelayedTask(this, new Runnable() {
                public void run() {
                    boolean ok = postJson(endpoint, json, cto, rto);
                    if (ok) {
                        getServer().getScheduler().scheduleSyncDelayedTask(BlockyDeposit.this, new Runnable() {
                            public void run() {
                                clearSlots(p, slotsToClear);
                                // --- MODIFIED ---
                                String msg = "Deposited (" + stacks + " stacks).";
                                if (finalIsFund) msg = msg + " to " + finalFundFactionName + " fund.";
                                p.sendMessage(ChatColor.GREEN + msg);
                                // --- END MODIFIED ---
                            }
                        });
                    } else {
                        p.sendMessage(ChatColor.RED + "Deposit failed (HTTP). Try again later.");
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
                
                // --- MODIFIED ---
                String msg = "Depositou items (" + stacks + " stacks).";
                if (finalIsFund) msg = msg + " to " + finalFundFactionName + " fund.";
                p.sendMessage(ChatColor.GREEN + msg);
                // --- END MODIFIED ---
                
            } catch (Exception e) {
                p.sendMessage(ChatColor.RED + "Deposito falhou.");
                LOG.warning("[Depositar] File write failed: " + e.getMessage());
            }
        }

        return true;
    }

    // ====== filtering ======
    // (This section is unchanged)
    private boolean canDeposit(ItemStack it) {
        if (it == null) return false;
        Material m = it.getType();
        if (m == null || m == Material.AIR) return false;
        if (it.getAmount() <= 0) return false;

        // Exclude any material with durability (tools, armor, etc.)
        try {
            if (m.getMaxDurability() > 0) return false;
        } catch (Throwable ignored) {
            // Very old jars should still have getMaxDurability; if not, allow list must handle it.
        }

        // Apply mode
        final int id = it.getTypeId();
        final String name = m.name(); // enum constant
        boolean listed = filterIds.contains(id) || filterNames.contains(name.toUpperCase());

        if ("inclusive".equalsIgnoreCase(filterMode)) {
            return listed; // only listed allowed
        } else {
            // exclusive (default): everything allowed except listed
            return !listed;
        }
    }

    // ====== collection ======
    // (This section is unchanged)
    private static class Agg {
        Map<String,Integer> qty = new HashMap<String,Integer>(); // "id:data" -> total
        Map<String,String>  name = new HashMap<String,String>(); // "id:data" -> name
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

    // ====== build JSON ======

    // --- MODIFIED ---
    // Added isFund and treasurer parameters
    private static String buildDepositJson(String username, String uuid, long ts, String hash, Agg a,
                                           boolean isFund, String treasurer) {
    // --- END MODIFIED ---
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"action\":\"deposit\",\n");
        sb.append("  \"timestamp\": ").append(ts).append(",\n");
        sb.append("  \"hash\":\"").append(jsonEscape(hash)).append("\",\n");
        sb.append("  \"username\":\"").append(jsonEscape(username)).append("\",\n");
        sb.append("  \"uuid\":\"").append(jsonEscape(uuid)).append("\",\n");
        // --- NEW ---
        sb.append("  \"fund\": ").append(isFund).append(",\n");
        sb.append("  \"treasurer\":\"").append(jsonEscape(treasurer)).append("\",\n");
        // --- END NEW ---
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

    // ====== clearing ======
    // (This section is unchanged)
    private static void clearSlots(Player p, int[] slots) {
        try {
            for (int s : slots) {
                if (s >= 0) p.getInventory().clear(s); // nulls the stack (safe for Beta)
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

    // ====== config & utils ======
    // (This section is unchanged)
    private void loadConfigProps() {
        File f = new File(dataDir, "config.properties");
        boolean fresh = !f.exists();
        if (fresh) {
            cfg.setProperty("mode", "file"); // "file" or "http"
            cfg.setProperty("output_dir", "out");
            cfg.setProperty("endpoint_url", "");
            cfg.setProperty("connect_timeout_ms", "1500");
            cfg.setProperty("read_timeout_ms", "2000");
            cfg.setProperty("filter_mode", "exclusive"); // inclusive | exclusive
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
        // parse filter every enable
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
            // numeric legacy ID?
            boolean allDigits = true;
            for (int k=0;k<t.length();k++) if (!Character.isDigit(t.charAt(k))) { allDigits=false; break; }
            if (allDigits) {
                try { filterIds.add(Integer.valueOf(t)); } catch (Exception ignored) {}
                continue;
            }
            // enum name
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

    // --- NEW ---
    /**
     * Iterates over faction YAML files to check if a player can deposit to a fund.
     * @param playerName The name of the player.
     * @return A FactionCheckResult object with the outcome.
     */
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
                // Use snakeyaml to parse
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) yaml.load(is);
                is.close();
                
                if (data == null) continue;

                String factionName = (String) data.get("nome");
                if (factionName == null || factionName.isEmpty()) factionName = file.getName().replace(".yml","");

                boolean isMember = false;
                
                // Check lider
                Object lider = data.get("lider");
                if (lider != null && lider.toString().equalsIgnoreCase(playerName)) {
                    isMember = true;
                }

                // Check oficiais
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
                
                // Check membros
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

                // Player is in this faction, check treasurer
                if (isMember) {
                    Object tesoureiroObj = data.get("tesoureiro");
                    String tesoureiro = (tesoureiroObj == null) ? "" : tesoureiroObj.toString().trim();
                    
                    if (tesoureiro.isEmpty()) {
                        return FactionCheckResult.fail("Nao ha tesoureiro definido para essa faccao " + factionName);
                    }
                    
                    // Success!
                    return FactionCheckResult.success(factionName, tesoureiro);
                }

            } catch (Exception e) {
                LOG.warning("[Depositar] Falha ao processar o arquivo da faccao: " + file.getName() + ": " + e.getMessage());
                // Continue to the next file
            }
        }

        // Not found in any faction
        return FactionCheckResult.fail("Voce nao faz parte de nenhuma faccao, nao eh possivel depositar no fundo da faccao.");
    }
    // --- END NEW ---
}