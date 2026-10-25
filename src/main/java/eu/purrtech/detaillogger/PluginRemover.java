package eu.purrtech.detaillogger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.Closeable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class PluginRemover {

    /**
     * @param self  tvůj vlastní plugin (this)
     * @param name  jméno pluginu ke smazání; pokud je to jméno self, smaže se plugin sám sebe
     * @return false pokud plugin neexistuje
     */
    public static boolean remove(Plugin self, String name) {
        Plugin target = Bukkit.getPluginManager().getPlugin(name);
        if (target == null) return false;

        File jar = jarOf(target);
        File data = target.getDataFolder();

        if (target == self) {                       // sám sebe nejde smazat za běhu -> po vypnutí JVM
            deleteOnShutdown(jar, data);
            Bukkit.getPluginManager().disablePlugin(target);
            return true;
        }

        Bukkit.getPluginManager().disablePlugin(target);
        try {                                       // uvolní zámek jaru (nutné na Windows)
            if (target.getClass().getClassLoader() instanceof Closeable c) c.close();
        } catch (Exception ignored) { }

        if (!delete(jar, data)) deleteOnShutdown(jar, data); // fallback, kdyby byl soubor stále zamčený
        return true;
    }

    private static File jarOf(Plugin p) {
        try {
            return new File(p.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean delete(File jar, File data) {
        rmrf(data.toPath());
        rmrf(jar.toPath());
        return !jar.exists() && !data.exists();
    }

    private static void rmrf(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        } catch (Exception ignored) { }
    }

    private static void deleteOnShutdown(File jar, File data) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (int i = 0; i < 20 && !delete(jar, data); i++) { // ponytail: 20x500ms retry, pak se vzdá
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }));
    }

}
