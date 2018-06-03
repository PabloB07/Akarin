package io.akarin.server.mixin.core;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.chunkio.ChunkIOExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import co.aikar.timings.MinecraftTimings;
import io.akarin.api.LogWrapper;
import net.minecraft.server.CrashReport;
import net.minecraft.server.CustomFunctionData;
import net.minecraft.server.EntityPlayer;
import net.minecraft.server.ITickable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.MojangStatisticsGenerator;
import net.minecraft.server.PacketPlayOutUpdateTime;
import net.minecraft.server.PlayerList;
import net.minecraft.server.ReportedException;
import net.minecraft.server.ServerConnection;
import net.minecraft.server.SystemUtils;
import net.minecraft.server.WorldServer;

@Mixin(value = MinecraftServer.class, remap = false)
public class MixinMinecraftServer {
    @Overwrite
    public String getServerModName() {
        return "Akarin";
    }
    
    /*
     * Forcely disable snooper
     */
    @Overwrite
    public void a(MojangStatisticsGenerator generator) {}
    
    @Overwrite
    public void b(MojangStatisticsGenerator generator) {}
    
    @Shadow public CraftServer server;
    @Shadow @Mutable protected Queue<FutureTask<?>> j;
    @Shadow public Queue<Runnable> processQueue;
    @Shadow private int ticks;
    @Shadow public List<WorldServer> worlds;
    @Shadow private PlayerList v;
    @Shadow @Final private List<ITickable> o;
    
    @Shadow public PlayerList getPlayerList() { return null; }
    @Shadow public ServerConnection an() { return null; }
    @Shadow public CustomFunctionData aL() { return null; }
    
    private final ExecutorCompletionService<Void> STAGE_WORLD_TICK = new ExecutorCompletionService<Void>(Executors.newFixedThreadPool(1, LogWrapper.STAGE_FACTORY));
    
    @Overwrite
    public void D() throws InterruptedException {
        MinecraftTimings.bukkitSchedulerTimer.startTiming();
        this.server.getScheduler().mainThreadHeartbeat(this.ticks);
        MinecraftTimings.bukkitSchedulerTimer.stopTiming();
        
        MinecraftTimings.minecraftSchedulerTimer.startTiming();
        FutureTask<?> entry;
        int count = this.j.size();
        while (count-- > 0 && (entry = this.j.poll()) != null) {
            SystemUtils.a(entry, MinecraftServer.LOGGER);
        }
        MinecraftTimings.minecraftSchedulerTimer.stopTiming();
        
        MinecraftTimings.processQueueTimer.startTiming();
        while (!processQueue.isEmpty()) {
            processQueue.remove().run();
        }
        MinecraftTimings.processQueueTimer.stopTiming();
        
        MinecraftTimings.chunkIOTickTimer.startTiming();
        ChunkIOExecutor.tick();
        MinecraftTimings.chunkIOTickTimer.stopTiming();
        
        MinecraftTimings.timeUpdateTimer.startTiming();
        // Send time updates to everyone, it will get the right time from the world the player is in.
        if (this.ticks % 20 == 0) {
            for (int i = 0; i < this.getPlayerList().players.size(); ++i) {
                EntityPlayer entityplayer = this.getPlayerList().players.get(i);
                entityplayer.playerConnection.sendPacket(new PacketPlayOutUpdateTime(entityplayer.world.getTime(), entityplayer.getPlayerTime(), entityplayer.world.getGameRules().getBoolean("doDaylightCycle"))); // Add support for per player time
            }
        }
        MinecraftTimings.timeUpdateTimer.stopTiming(); // Spigot
        
        for (int i = 0; i < worlds.size(); ++i) { // CraftBukkit
            WorldServer worldserver = worlds.get(i);
            // TODO Fix this feature
            // TileEntityHopper.skipHopperEvents = worldserver.paperConfig.disableHopperMoveEvents || org.bukkit.event.inventory.InventoryMoveItemEvent.getHandlerList().getRegisteredListeners().length == 0;
            
            Runnable tick = () -> {
                try {
                    worldserver.doTick();
                } catch (Throwable throwable) {
                    CrashReport crashreport;
                    try {
                        crashreport = CrashReport.a(throwable, "Exception ticking world");
                    } catch (Throwable t){
                        throw new RuntimeException("Error generating crash report", t);
                    }
                    worldserver.a(crashreport);
                    throw new ReportedException(crashreport);
                }
            };
            
            int size = worlds.size();
            if (size > 1) {
                LogWrapper.silentTiming = true;
                STAGE_WORLD_TICK.submit(tick, null);
            } else {
                worldserver.timings.doTick.startTiming();
                tick.run();
                worldserver.timings.doTick.stopTiming();
            }
            
            try {
                worldserver.timings.tickEntities.startTiming();
                worlds.get(i + 1 < size ? i + 1 : 0).tickEntities();
                worldserver.timings.tickEntities.stopTiming();
            } catch (Throwable throwable) {
                CrashReport crashreport;
                try {
                    crashreport = CrashReport.a(throwable, "Exception ticking world entities");
                } catch (Throwable t){
                    throw new RuntimeException("Error generating crash report", t);
                }
                worldserver.a(crashreport);
                throw new ReportedException(crashreport);
            }
            
            if (size > 1) {
                worldserver.timings.doTick.startTiming();
                STAGE_WORLD_TICK.take();
                worldserver.timings.doTick.stopTiming();
                LogWrapper.silentTiming = false;
            }
            
            worldserver.getTracker().updatePlayers();
            worldserver.explosionDensityCache.clear(); // Paper - Optimize explosions
        }
        
        MinecraftTimings.connectionTimer.startTiming();
        this.an().c();
        MinecraftTimings.connectionTimer.stopTiming();
        
        MinecraftTimings.playerListTimer.startTiming();
        this.v.tick();
        MinecraftTimings.playerListTimer.stopTiming();
        
        MinecraftTimings.commandFunctionsTimer.startTiming();
        this.aL().e();
        MinecraftTimings.commandFunctionsTimer.stopTiming();
        
        MinecraftTimings.tickablesTimer.startTiming();
        for (int i = 0; i < this.o.size(); ++i) {
            this.o.get(i).e();
        }
        MinecraftTimings.tickablesTimer.stopTiming();
    }
    
}
