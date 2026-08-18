package dev.j3fftw.worldeditslimefun;

import dev.j3fftw.worldeditslimefun.commands.WorldEditSlimefunCommands;
import dev.j3fftw.worldeditslimefun.listeners.RegistryListener;
import dev.j3fftw.worldeditslimefun.listeners.WandListener;
import dev.j3fftw.worldeditslimefun.slimefun.Items;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;

public final class WorldEditSlimefun extends JavaPlugin implements SlimefunAddon {

    private static WorldEditSlimefun instance;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        new Metrics(this, 20799);

        Items.init(this);
        WorldEditSlimefunCommands.init(this);

        PluginManager manager = Bukkit.getPluginManager();
        manager.registerEvents(new WandListener(), this);
        manager.registerEvents(new RegistryListener(), this);

        getLogger().info("WorldEditSlimefun enabled with WorldEdit/FAWE selection support.");
    }

    @Override
    public void onDisable() {
        instance = null;
    }

    @Nonnull
    @Override
    public JavaPlugin getJavaPlugin() {
        return this;
    }

    @Nonnull
    @Override
    public String getBugTrackerURL() {
        return "https://github.com/wickidcow/WorldEditSlimefun/issues";
    }

    public static WorldEditSlimefun getInstance() {
        return instance;
    }
}
