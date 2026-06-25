package me.gadse.antiseedcracker;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.github.Anon8281.universalScheduler.UniversalScheduler;
import com.github.Anon8281.universalScheduler.scheduling.schedulers.TaskScheduler;
import me.gadse.antiseedcracker.commands.AntiSeedCrackerCommand;
import me.gadse.antiseedcracker.listeners.DragonRespawnSpikeModifier;
import me.gadse.antiseedcracker.listeners.EndCityModifier;
import me.gadse.antiseedcracker.packets.ServerLogin;
import me.gadse.antiseedcracker.packets.ServerRespawn;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class AntiSeedCracker extends JavaPlugin implements CommandExecutor {

    private ProtocolManager protocolManager;
    private NamespacedKey modifiedSpike;

    private DragonRespawnSpikeModifier dragonRespawnspikeModifier;
    private EndCityModifier endCityModifier;

    private static TaskScheduler scheduler;
    private final Map<Long, Long> randomizedSeeds = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Config folder can not be written. Check read/write permissions.");
        }
        saveDefaultConfig();

        scheduler = UniversalScheduler.getScheduler(this);

        protocolManager = ProtocolLibrary.getProtocolManager();
        modifiedSpike = new NamespacedKey(this, "modified-spike");
        dragonRespawnspikeModifier = new DragonRespawnSpikeModifier(this);
        endCityModifier = new EndCityModifier(this);

        PluginCommand command = getCommand("antiseedcracker");
        if (command == null) {
            getLogger().severe("The antiseedcracker command is missing from plugin.yml.");
        } else {
            command.setExecutor(new AntiSeedCrackerCommand(this));
        }

        reload(true);
    }

    public static TaskScheduler getScheduler() {
        return scheduler;
    }

    public void reload(boolean isOnEnable) {
        if (!isOnEnable) {
            protocolManager.removePacketListeners(this);
            dragonRespawnspikeModifier.unregister();
            endCityModifier.unregister();
        }

        if (getConfig().getBoolean("randomize_hashed_seed.login", true)) {
            protocolManager.addPacketListener(new ServerLogin(this));
        }

        if (getConfig().getBoolean("randomize_hashed_seed.respawn", true)) {
            protocolManager.addPacketListener(new ServerRespawn(this));
        }

        if (getConfig().getBoolean("modifiers.end_spikes.enabled", false)) {
            getServer().getWorlds().forEach(world -> {
                if (!getConfig().getStringList("modifiers.end_spikes.worlds").contains(world.getName())) {
                    return;
                }

                if (world.getEnvironment() != World.Environment.THE_END) {
                    getLogger().warning(String.format("The world '%s' is not an end dimension, it will be ignored.", world.getName()));
                    return;
                }

                getScheduler().runTask(new Location(world, 0, 0, 0), () -> modifyEndSpikes(world));
            });
            getServer().getPluginManager().registerEvents(dragonRespawnspikeModifier, this);
        }

        if (getConfig().getBoolean("modifiers.end_cities.enabled", false)) {
            getServer().getPluginManager().registerEvents(endCityModifier, this);
        }
    }

    @Override
    public void onDisable() {
        protocolManager.removePacketListeners(this);
        dragonRespawnspikeModifier.unregister();
        endCityModifier.unregister();
    }

    public long randomizeHashedSeed(long hashedSeed) {
        return randomizedSeeds.computeIfAbsent(hashedSeed, seed -> {
            int length = Long.toString(seed).length();
            if (length > 18) {
                length = 18;
            }
            long min = (long) Math.pow(10, length - 1);
            long max = (long) (Math.pow(10, length) - 1);
            return ThreadLocalRandom.current().nextLong(min, max + 1);
        });
    }

    private final List<Integer> spikeHeights = List.of(76, 79, 82, 85, 88, 91, 94, 97, 100, 103);

    public void modifyEndSpikes(World world) {
        if (world.getEnvironment() != World.Environment.THE_END
                || world.getPersistentDataContainer().getOrDefault(modifiedSpike, PersistentDataType.BOOLEAN, false)) {
            return;
        }

        getBedrockBlocksByHeightAsync(world).thenAccept(bedrockBlocksByHeight -> {
            getScheduler().runTask(new Location(world, 0, 0, 0), () -> {
                if (getConfig().getString("modifiers.end_spikes.mode", "swap").equalsIgnoreCase("swap")) {
                    swapEndSpikes(world, bedrockBlocksByHeight);
                } else {
                    moveEndSpike(world, bedrockBlocksByHeight);
                }
            });
        });
    }

    private void swapEndSpikes(World world, Map<Integer, Block> bedrockBlocksByHeight) {
        int randomSpikeIndex = ThreadLocalRandom.current().nextInt(spikeHeights.size());
        int nextSpikeIndex = randomSpikeIndex + 1 > spikeHeights.size() - 1 ? 0 : randomSpikeIndex + 1;
        Block spike_one = bedrockBlocksByHeight.get(spikeHeights.get(randomSpikeIndex));
        Block spike_two = bedrockBlocksByHeight.get(spikeHeights.get(nextSpikeIndex));

        if (spike_one == null || spike_two == null) return;

        spike_one.setType(Material.OBSIDIAN);
        new Location(world, spike_one.getX(), spike_two.getY(), spike_one.getZ()).getBlock().setType(Material.BEDROCK);

        spike_two.setType(Material.OBSIDIAN);
        new Location(world, spike_two.getX(), spike_one.getY(), spike_two.getZ()).getBlock().setType(Material.BEDROCK);

        world.getPersistentDataContainer().set(modifiedSpike, PersistentDataType.BOOLEAN, true);
    }

    private void moveEndSpike(World world, Map<Integer, Block> bedrockBlocksByHeight) {
        int randomSpikeIndex = ThreadLocalRandom.current().nextInt(spikeHeights.size());
        Block endSpike = bedrockBlocksByHeight.get(spikeHeights.get(randomSpikeIndex));

        if (endSpike == null) return;

        endSpike.setType(Material.OBSIDIAN);
        endSpike.getRelative(BlockFace.DOWN).setType(Material.BEDROCK);

        world.getPersistentDataContainer().set(modifiedSpike, PersistentDataType.BOOLEAN, true);
    }

    public CompletableFuture<Map<Integer, Block>> getBedrockBlocksByHeightAsync(World world) {
        List<CompletableFuture<Block>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double x = 42.0 * Math.cos(2.0 * (-Math.PI + 0.3141592653589793 * i));
            double z = 42.0 * Math.sin(2.0 * (-Math.PI + 0.3141592653589793 * i));
            Location loc = new Location(world, x, 0, z);

            CompletableFuture<Block> future = new CompletableFuture<>();
            getScheduler().runTask(loc, () -> {
                Block block = world.getHighestBlockAt(loc);
                while (block.getType() != Material.BEDROCK && block.getY() > 0) {
                    block = block.getRelative(BlockFace.DOWN);
                }
                future.complete(block);
            });
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(v -> {
            Map<Integer, Block> bedrockBlocksByHeight = new HashMap<>(10);
            for (CompletableFuture<Block> future : futures) {
                Block block = future.join();
                bedrockBlocksByHeight.put(block.getY(), block);
            }
            return bedrockBlocksByHeight;
        });
    }

    public NamespacedKey getModifiedSpike() {
        return modifiedSpike;
    }
}