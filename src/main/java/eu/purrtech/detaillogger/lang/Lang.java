package eu.purrtech.detaillogger.lang;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

/**
 * Server-wide plugin language ({@code language:} in config.yml, "cs" or "en") - every player sees the
 * same language no matter their own client locale. Texts live in {@code lang/<code>.yml} in the data
 * folder (copied from the jar on first start, so the server can edit them). A key missing there falls
 * back to the jar's copy of the same language, then to English, then to the key itself.
 * Placeholders are {@code {0}}, {@code {1}}, ... in the order of {@link #t}'s arguments.
 */
public final class Lang {

    public static final List<String> BUNDLED = List.of("cs", "en");

    private static YamlConfiguration active = new YamlConfiguration();
    private static YamlConfiguration bundledActive = new YamlConfiguration();
    private static YamlConfiguration english = new YamlConfiguration();
    private static String code = "en";

    private Lang() {
    }

    /** (Re)loads the language from config - call on enable and on reload. */
    public static void load(JavaPlugin plugin) {
        Logger log = plugin.getLogger();
        String wanted = plugin.getConfig().getString("language", "cs").trim().toLowerCase(java.util.Locale.ROOT);
        for (String lang : BUNDLED) {
            if (!new File(plugin.getDataFolder(), "lang/" + lang + ".yml").exists()) {
                plugin.saveResource("lang/" + lang + ".yml", false);
            }
        }
        File file = new File(plugin.getDataFolder(), "lang/" + wanted + ".yml");
        if (!file.exists()) {
            log.warning("language '" + wanted + "': lang/" + wanted + ".yml not found, using English.");
            wanted = "en";
            file = new File(plugin.getDataFolder(), "lang/en.yml");
        }
        code = wanted;
        active = YamlConfiguration.loadConfiguration(file);
        bundledActive = bundled(plugin, wanted);
        english = bundled(plugin, "en");
        log.info("Language: " + code);
    }

    private static YamlConfiguration bundled(JavaPlugin plugin, String lang) {
        try (InputStream in = plugin.getResource("lang/" + lang + ".yml")) {
            return in == null ? new YamlConfiguration()
                    : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return new YamlConfiguration();
        }
    }

    public static String code() {
        return code;
    }

    /** Text for {@code key} with {@code {0}}, {@code {1}}... replaced by {@code args}. */
    public static String t(String key, Object... args) {
        String text = active.getString(key);
        if (text == null) text = bundledActive.getString(key);
        if (text == null) text = english.getString(key);
        if (text == null) return key;
        for (int i = 0; i < args.length; i++) {
            text = text.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return text;
    }

    /** A list value (e.g. month names); empty when missing everywhere. */
    public static List<String> list(String key) {
        if (active.isList(key)) return active.getStringList(key);
        if (bundledActive.isList(key)) return bundledActive.getStringList(key);
        return english.getStringList(key);
    }
}
