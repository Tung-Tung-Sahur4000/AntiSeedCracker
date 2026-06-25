package me.gadse.antiseedcracker.listeners;

import com.github.Anon8281.universalScheduler.scheduling.tasks.MyScheduledTask;
import me.gadse.antiseedcracker.AntiSeedCracker;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.persistence.PersistentDataType;

public class DragonRespawnSpikeModifier implements Listener {

    private final AntiSeedCracker plugin;
    private final java.util.Set<java.util.UUID> scheduledWorlds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final EntityType crystalType = EntityType.END_CRYSTAL;

    public DragonRespawnSpikeModifier(AntiSeedCracker plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerPlaceRespawnCrystals(EntityPlaceEvent event) {
        World world = event.getEntity().getWorld();
        if (event.getEntityType() != crystalType
                || world.getEnvironment() != World.Environment.THE_END
                || !plugin.getConfig().getStringList("modifiers.end_spikes.worlds").contains(world.getName())) {
            return;
        }

        Location portalCenter = new Location(world, 0, 65, 0);
        AntiSeedCracker.getScheduler().runTask(portalCenter, () -> {
            if (event.getBlock().getType() != Material.BEDROCK
                    || isOutsidePortalRadius(event.getBlock().getLocation())
                    || getAmountOfEnderCrystalsOnPortal(world) != 3
                    || scheduledWorlds.contains(world.getUID())) {
                return;
            }

            world.getPersistentDataContainer().set(plugin.getModifiedSpike(), PersistentDataType.BOOLEAN, false);
            scheduledWorlds.add(world.getUID());

            final MyScheduledTask[] repeatingTask = new MyScheduledTask[1];
            repeatingTask[0] = AntiSeedCracker.getScheduler().runTaskTimer(portalCenter, () -> {
                DragonBattle dragonBattle = world.getEnderDragonBattle();
                if (dragonBattle == null) {
                    plugin.modifyEndSpikes(world);
                    if (repeatingTask[0] != null) repeatingTask[0].cancel();
                    scheduledWorlds.remove(world.getUID());
                    return;
                }

                if (dragonBattle.getRespawnPhase() == DragonBattle.RespawnPhase.START
                        || dragonBattle.getRespawnPhase() == DragonBattle.RespawnPhase.PREPARING_TO_SUMMON_PILLARS
                        || dragonBattle.getRespawnPhase() == DragonBattle.RespawnPhase.SUMMONING_PILLARS) {
                    return;
                }

                plugin.modifyEndSpikes(world);
                scheduledWorlds.remove(world.getUID());
                if (repeatingTask[0] != null) repeatingTask[0].cancel();
            }, 300L, 20L);
        });
    }

    private int getAmountOfEnderCrystalsOnPortal(World world) {
        Location endLocation = new Location(world, 0, 65, 0);
        return world.getNearbyEntities(
                endLocation, 7, 3, 7, entity -> entity instanceof EnderCrystal
                        && entity.getLocation().getBlock().getRelative(BlockFace.DOWN).getType() == Material.BEDROCK
        ).size();
    }

    private boolean isOutsidePortalRadius(Location location) {
        return location.getX() < -3 || location.getX() > 3 || location.getZ() < -3 || location.getZ() > 3;
    }

    public void unregister() {
        EntityPlaceEvent.getHandlerList().unregister(this);
    }
}