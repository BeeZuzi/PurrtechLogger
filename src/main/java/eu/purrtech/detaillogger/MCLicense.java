package eu.purrtech.detaillogger;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class MCLicense {
    // ==================== Constants ====================
    private static final Logger LOGGER = Logger.getLogger("MCLicense");

    // API Endpoints
    private static final String API_BASE_URL = "https://api.mclicense.org";
    private static final String API_URL = API_BASE_URL + "/validate/%s/%s";  // For license validation (pluginId, key)
    private static final String HEARTBEAT_URL = API_BASE_URL + "/heartbeat/%s/%s";  // For heartbeat (pluginId, key)

    // Connection Settings
    private static final int TIMEOUT_MS = 5000;  // 5 second timeout for HTTP requests

    // Heartbeat Settings
    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    // RSA Public Key for signature verification
    private static final String PUBLIC_KEY =
            "-----BEGIN PUBLIC KEY-----\n" +
                    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArhw7oQaOrgCzUxDi5D+N\n" +
                    "TH9te0JYB1EvW7CEI40+n2drmHJ4+g0CXXYjJc5LyuePskSUPnnHf3UkRvi1GTUd\n" +
                    "6Bqi2Jpeu+qbBfm3hg6rcyLUWo8d5MrBQDbVcIvKmQNegTaJGxFRpEFR9XOeHI1g\n" +
                    "4dfF+hOfy+1rbEF4p4fgiz0irtKv8l3uSPOKVoEjTL9xnZx4MU5rIsn6W3jee04q\n" +
                    "ESPJpCg8nmmZSuJ+9EzzoLnLnUc2/sBuqJ/jexpNfMrXIR11+L8DFqei7J2M7aKi\n" +
                    "0KZvQNIqzqTPBCR9VLZPBjFu6cYT/E/WUjjFROuRhi+7Xsa6tKLqoiO4VwJSrn5L\n" +
                    "zwIDAQAB\n" +
                    "-----END PUBLIC KEY-----";

    private static final boolean IS_FOLIA = isRunningFolia();

    // ==================== Marketplace Provider Fields ====================
    private static String pmPlaceholder = "%%__POLYMART__%%";
    private static String pmLicense = "%%__LICENSE__%%";
    private static String pmUser = "%%__USER__%%";
    private static String bbbLicense = "%%__BBB_LICENSE__%%";

    // ==================== Heartbeat Manager Fields ====================
    private static boolean heartbeatRunning = false;
    private static String heartbeatPluginId;
    private static String heartbeatKey;
    private static String heartbeatSessionId;
    private static ScheduledTask foliaTask;
    private static BukkitTask bukkitTask;
    private static boolean listenerRegistered = false;

    // ==================== Initialization ====================
    private static boolean isRunningFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ==================== Public License Validation Methods ====================

    /**
     * Validates a license key with the MCLicense validation server.
     * <p>
     * This function performs several checks:
     * <ul>
     *     <li>Verifies the existence and content of the license file</li>
     *     <li>Validates the license key with the remote server</li>
     *     <li>Verifies the response signature for security</li>
     *     <li>Ensures the response matches the requested plugin and key</li>
     *     <li>Checks if the license is valid and not expired, reached max IPs, etc</li>
     * </ul>
     *
     * The license key should be placed in a file named 'mclicense.txt' in the plugin's data folder by the user, or be hardcoded by a marketplace.
     *
     * @param plugin The JavaPlugin instance requesting validation
     * @param pluginId The unique identifier assigned to your plugin by MCLicense
     * @return true if the license is valid and active, false otherwise
     */
    public static boolean validateKey(JavaPlugin plugin, String pluginId) {
        try {
            // Check if license file exists or create it
            File licenseFile = new File(plugin.getDataFolder(), "mclicense.txt");
            String fileContent = "";
            if (!licenseFile.exists()) {
                plugin.getDataFolder().mkdirs();
                licenseFile.createNewFile();
            } else {
                fileContent = new String(Files.readAllBytes(Paths.get(licenseFile.getPath())), StandardCharsets.UTF_8).trim();
            }

            // Read the license key from the file
            String key = fileContent;
            boolean usedHardcodedFallback = false;
            if (key.isEmpty()) {
                // Assuming first run, use hardcoded if exists, else prompt
                String hardcodedKey = getHardcodedLicense();
                if (hardcodedKey != null) {
                    key = hardcodedKey;
                    usedHardcodedFallback = true;
                    Files.write(licenseFile.toPath(), key.getBytes(StandardCharsets.UTF_8));
                } else {
                    LOGGER.info("License key is empty for " + plugin.getName() + "! Place your key in the 'mclicense.txt' file in the plugin folder and restart the server.");
                    return false;
                }
            }

            boolean isValid = validateLicenseWithServer(plugin, pluginId, key, licenseFile);

            // Keep file synchronized with validated key when key source changed
            if (isValid && !usedHardcodedFallback && !key.equals(fileContent)) {
                Files.write(licenseFile.toPath(), key.getBytes(StandardCharsets.UTF_8));
            }

            return isValid;
        } catch (Exception e) {
            LOGGER.info("License validation failed for " + plugin.getName() + " (System error)");
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Writes a license key to the license file and validates it immediately.
     *
     * @param plugin The JavaPlugin instance requesting validation
     * @param pluginId The unique identifier assigned to your plugin by MCLicense
     * @param key The license key to write
     * @return true if the license is valid and active, false otherwise
     */
    public static boolean writeAndValidate(JavaPlugin plugin, String pluginId, String key) {
        try {
            // Check if license file exists or create it
            File licenseFile = new File(plugin.getDataFolder(), "mclicense.txt");
            if (!licenseFile.exists()) {
                plugin.getDataFolder().mkdirs();
                licenseFile.createNewFile();
            }

            // Write key
            Files.write(licenseFile.toPath(), key.getBytes(StandardCharsets.UTF_8));

            // Validate the license
            return validateLicenseWithServer(plugin, pluginId, key, licenseFile);
        } catch (Exception e) {
            LOGGER.info("License write and validation failed for " + plugin.getName() + " (System error)");
            e.printStackTrace();
            return false;
        }
    }

    // ==================== Private License Validation Methods ====================

    /**
     * Internal function that handles the actual license validation with the MCLicense server.
     *
     * @param plugin The JavaPlugin instance requesting validation
     * @param pluginId The unique identifier assigned to your plugin by MCLicense
     * @param key The license key to validate
     * @param licenseFile The license file being used
     * @return true if the license is valid and active, false otherwise
     */
    private static boolean validateLicenseWithServer(JavaPlugin plugin, String pluginId, String key, File licenseFile) {
        try {
            String sessionId = UUID.randomUUID().toString();
            String nonce = UUID.randomUUID().toString();

            // Properly encode all URL components
            String encodedPluginId = URLEncoder.encode(pluginId, StandardCharsets.UTF_8.toString()).replace("+", "%20");
            String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.toString()).replace("+", "%20");

            String baseUrl = String.format(API_URL, encodedPluginId, encodedKey) +
                    "?sessionId=" + sessionId +
                    "&nonce=" + nonce;

            String polymartUserId = getPolymartUserId();
            if (polymartUserId != null) {
                baseUrl += "&polymartUserId=" + URLEncoder.encode(polymartUserId, StandardCharsets.UTF_8.toString());
            }

            URL url = new URL(baseUrl);

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);

            // Read the response from the server
            String response;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getResponseCode() >= 400 ?
                    connection.getErrorStream() : connection.getInputStream()))) {
                StringBuilder responseBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
                response = responseBuilder.toString();
            }

            // Reject if the response code is not 200
            if (connection.getResponseCode() != 200) {
                try {
                    LOGGER.info("License validation failed for " + plugin.getName() + " (" + new JSONObject(response).getString("message") + ")");
                } catch (Exception e) {
                    LOGGER.info("License validation failed for " + plugin.getName() + " (Server error)");
                }
                return false;
            }

            JSONObject responseJson = new JSONObject(response);

            // Verify nonce is what was sent
            if (!responseJson.getString("nonce").equals(nonce)) {
                LOGGER.info("License validation failed for " + plugin.getName() + " (Nonce mismatch)");
                return false;
            }

            // Verify key and pluginId are what was sent
            if (!responseJson.getString("key").equals(key) || !responseJson.getString("pluginId").equals(pluginId)) {
                LOGGER.info("License validation failed for " + plugin.getName() + " (Key or pluginId mismatch)");
                return false;
            }

            // Verify the response signature
            String signature = responseJson.getString("signature");

            JSONObject dataToVerify = new JSONObject();
            dataToVerify.put("key", responseJson.getString("key"));
            dataToVerify.put("pluginId", responseJson.getString("pluginId"));
            dataToVerify.put("status", responseJson.getString("status"));
            dataToVerify.put("message", responseJson.getString("message"));
            dataToVerify.put("nonce", responseJson.getString("nonce"));

            String data = dataToVerify.toString();

            String publicKeyPEM = PUBLIC_KEY
                    .replace("-----BEGIN PUBLIC KEY-----\n", "")
                    .replace("\n-----END PUBLIC KEY-----", "")
                    .replaceAll("\n", "");

            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyPEM);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(data.getBytes(StandardCharsets.UTF_8));

            boolean isValid = sig.verify(Base64.getDecoder().decode(signature));
            if (!isValid) {
                LOGGER.info("License validation failed for " + plugin.getName() + " (Signature mismatch)");
                return false;
            }

            startHeartbeat(plugin, pluginId, key, sessionId);

            LOGGER.info("License validation succeeded for " + plugin.getName() + "!");
            return true;
        } catch (Exception e) {
            LOGGER.info("License validation failed for " + plugin.getName() + " (System error)");
            e.printStackTrace();
            return false;
        }
    }

    // ==================== Heartbeat Manager Methods ====================

    protected static void startHeartbeat(JavaPlugin plugin, String pluginId, String key, String sessionId) {
        if (heartbeatRunning) {
            killHeartbeat();
        }

        if (!listenerRegistered) {
            plugin.getServer().getPluginManager().registerEvents(new ShutdownListenerImpl(plugin), plugin);
            listenerRegistered = true;
        }

        heartbeatPluginId = pluginId;
        heartbeatKey = key;
        heartbeatSessionId = sessionId;

        if (IS_FOLIA) {
            foliaTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, (task) -> sendHeartbeat(false), HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        } else {
            bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> sendHeartbeat(false), HEARTBEAT_INTERVAL_SECONDS * 20L, HEARTBEAT_INTERVAL_SECONDS * 20L);
        }

        heartbeatRunning = true;
    }

    protected static void sendHeartbeat(boolean isShutdown) {
        try {
            URL url = new URL(String.format(HEARTBEAT_URL, heartbeatPluginId, heartbeatKey));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);

            // Create JSON payload
            JSONObject payload = new JSONObject();
            payload.put("serverIp", heartbeatSessionId);
            if (isShutdown) {
                payload.put("shutdown", true);
            }

            // Send the heartbeat
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            if (connection.getResponseCode() != 200) {
                // Ignore
            }
        } catch (Exception x) {
            // Ignore heartbeat errors
        }
    }

    private static void killHeartbeat() {
        // Send shutdown heartbeat
        sendHeartbeat(true);

        // Safely cancel tasks
        if (IS_FOLIA && foliaTask != null) {
            foliaTask.cancel();
            foliaTask = null;
        } else if (!IS_FOLIA && bukkitTask != null) {
            bukkitTask.cancel();
            bukkitTask = null;
        }

        heartbeatRunning = false;
    }

    // ==================== Marketplace Provider Methods ====================

    private static String getHardcodedLicense() {
        if (!bbbLicense.startsWith("%%__")) {
            return bbbLicense;
        } else if (pmPlaceholder.equals("1") && !pmLicense.startsWith("%%__")) {
            return "pm_" + pmLicense;
        }
        return null;
    }

    private static String getPolymartUserId() {
        if (pmPlaceholder.equals("1") && !pmUser.startsWith("%%__")) {
            return pmUser;
        }
        return null;
    }

    // ==================== Shutdown Listener ====================

    private static class ShutdownListenerImpl implements Listener {
        private final JavaPlugin activePlugin;

        public ShutdownListenerImpl(JavaPlugin plugin) {
            this.activePlugin = plugin;
        }

        @EventHandler
        public void onPluginDisable(PluginDisableEvent event) {
            if (event.getPlugin() == activePlugin) {
                try {
                    sendHeartbeat(true);
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }
}

