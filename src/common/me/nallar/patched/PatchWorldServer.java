package me.nallar.patched;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import com.google.common.collect.ImmutableSetMultimap;

import me.nallar.tickthreading.Log;
import me.nallar.tickthreading.collections.TreeHashSet;
import me.nallar.tickthreading.minecraft.ThreadManager;
import me.nallar.tickthreading.minecraft.TickThreading;
import me.nallar.tickthreading.patcher.Declare;
import net.minecraft.block.Block;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ReportedException;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.SpawnerAnimals;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.ForgeChunkManager;

public abstract class PatchWorldServer extends WorldServer implements Runnable {
	private Iterator chunkCoordIterator;
	private ThreadManager threadManager;
	private ThreadLocal<Random> randoms;
	@Declare
	public int tickCount_;

	public PatchWorldServer(MinecraftServer par1MinecraftServer, ISaveHandler par2ISaveHandler, String par3Str, int par4, WorldSettings par5WorldSettings, Profiler par6Profiler) {
		super(par1MinecraftServer, par2ISaveHandler, par3Str, par4, par5WorldSettings, par6Profiler);
	}

	public void construct() {
		randoms = new ThreadLocalRandom();
		threadManager = new ThreadManager(TickThreading.instance.getThreadCount(), "Chunk Updates for " + Log.name(this));
		field_73064_N = null;
		pendingTickListEntries = new TreeHashSet();
	}

	@Override
	protected void initialize(WorldSettings par1WorldSettings) {
		if (this.entityIdMap == null) {
			this.entityIdMap = new net.minecraft.util.IntHashMap();
		}

		if (this.pendingTickListEntries == null) {
			this.pendingTickListEntries = new TreeHashSet();
		}

		this.createSpawnPosition(par1WorldSettings);
	}

	@Override
	public List getPendingBlockUpdates(Chunk par1Chunk, boolean par2) {
		ArrayList var3 = null;
		ChunkCoordIntPair var4 = par1Chunk.getChunkCoordIntPair();
		int var5 = var4.chunkXPos << 4;
		int var6 = var5 + 16;
		int var7 = var4.chunkZPos << 4;
		int var8 = var7 + 16;
		synchronized (pendingTickListEntries) {
			Iterator var9 = this.pendingTickListEntries.iterator();

			while (var9.hasNext()) {
				NextTickListEntry var10 = (NextTickListEntry) var9.next();

				if (var10.xCoord >= var5 && var10.xCoord < var6 && var10.zCoord >= var7 && var10.zCoord < var8) {
					if (par2) {
						var9.remove();
					}

					if (var3 == null) {
						var3 = new ArrayList();
					}

					var3.add(var10);
				}
			}
		}

		return var3;
	}

	@Override
	public void func_82740_a(int par1, int par2, int par3, int par4, int par5, int par6) {
		NextTickListEntry var7 = new NextTickListEntry(par1, par2, par3, par4);
		boolean isForced = getPersistentChunks().containsKey(new ChunkCoordIntPair(var7.xCoord >> 4, var7.zCoord >> 4));
		byte var8 = isForced ? (byte) 0 : 8;

		if (this.scheduledUpdatesAreImmediate && par4 > 0) {
			if (Block.blocksList[par4].func_82506_l()) {
				if (this.checkChunksExist(var7.xCoord - var8, var7.yCoord - var8, var7.zCoord - var8, var7.xCoord + var8, var7.yCoord + var8, var7.zCoord + var8)) {
					int var9 = this.getBlockId(var7.xCoord, var7.yCoord, var7.zCoord);

					if (var9 == var7.blockID && var9 > 0) {
						Block.blocksList[var9].updateTick(this, var7.xCoord, var7.yCoord, var7.zCoord, this.rand);
					}
				}

				return;
			}

			par5 = 1;
		}

		if (this.checkChunksExist(par1 - var8, par2 - var8, par3 - var8, par1 + var8, par2 + var8, par3 + var8)) {
			if (par4 > 0) {
				var7.setScheduledTime((long) par5 + this.worldInfo.getWorldTotalTime());
				var7.func_82753_a(par6);
			}

			synchronized (pendingTickListEntries) {
				this.pendingTickListEntries.add(var7);
			}
		}
	}

	@Override
	public void scheduleBlockUpdateFromLoad(int par1, int par2, int par3, int par4, int par5) {
		NextTickListEntry var6 = new NextTickListEntry(par1, par2, par3, par4);

		if (par4 > 0) {
			var6.setScheduledTime((long) par5 + this.worldInfo.getWorldTotalTime());
		}

		synchronized (pendingTickListEntries) {
			this.pendingTickListEntries.add(var6);
		}
	}

	@Override
	public boolean tickUpdates(boolean par1) {
		synchronized (pendingTickListEntries) {
			int var2 = Math.min(1000, this.pendingTickListEntries.size());

			ImmutableSetMultimap<ChunkCoordIntPair, ForgeChunkManager.Ticket> persistentChunks = getPersistentChunks();
			for (int var3 = 0; var3 < var2; ++var3) {
				NextTickListEntry var4 = (NextTickListEntry) this.pendingTickListEntries.first();

				if (!par1 && var4.scheduledTime > this.worldInfo.getWorldTotalTime()) {
					break;
				}

				this.pendingTickListEntries.remove(var4);
				boolean isForced = persistentChunks.containsKey(new ChunkCoordIntPair(var4.xCoord >> 4, var4.zCoord >> 4));
				byte var5 = isForced ? (byte) 0 : 8;

				if (this.checkChunksExist(var4.xCoord - var5, var4.yCoord - var5, var4.zCoord - var5, var4.xCoord + var5, var4.yCoord + var5, var4.zCoord + var5)) {
					int var6 = this.getBlockId(var4.xCoord, var4.yCoord, var4.zCoord);

					if (var6 == var4.blockID && var6 > 0) {
						try {
							Block.blocksList[var6].updateTick(this, var4.xCoord, var4.yCoord, var4.zCoord, this.rand);
						} catch (Throwable var13) {
							CrashReport var8 = CrashReport.makeCrashReport(var13, "Exception while ticking a block");
							CrashReportCategory var9 = var8.makeCategory("Block being ticked");
							int var10;

							try {
								var10 = this.getBlockMetadata(var4.xCoord, var4.yCoord, var4.zCoord);
							} catch (Throwable var12) {
								var10 = -1;
							}

							CrashReportCategory.func_85068_a(var9, var4.xCoord, var4.yCoord, var4.zCoord, var6, var10);
							throw new ReportedException(var8);
						}
					}
				}
			}

			return !this.pendingTickListEntries.isEmpty();
		}
	}

	@Override
	public void tick() {
		int tickCount = this.tickCount++;
		this.updateWeather();
		if (this.difficultySetting < 3 && this.getWorldInfo().isHardcoreModeEnabled()) {
			this.difficultySetting = 3;
		}

		if (tickCount % 200 == 0) {
			this.provider.worldChunkMgr.cleanupCache();
		}

		if (this.areAllPlayersAsleep()) {
			long var2 = this.worldInfo.getWorldTime();
			this.worldInfo.setWorldTime(var2 + var2 % 24000L);
			this.wakeAllPlayers();
		}

		this.theProfiler.startSection("mobSpawner");

		if (this.getGameRules().getGameRuleBooleanValue("doMobSpawning")) {
			SpawnerAnimals.findChunksForSpawning(this, this.spawnHostileMobs, this.spawnPeacefulMobs, this.worldInfo.getWorldTotalTime() % 400L == 0L);
		}

		this.theProfiler.endStartSection("chunkSource");
		this.chunkProvider.unload100OldestChunks();
		this.skylightSubtracted = this.calculateSkylightSubtracted(1.0F);

		this.sendAndApplyBlockEvents();
		this.worldInfo.incrementTotalWorldTime(this.worldInfo.getWorldTotalTime() + 1L);
		this.worldInfo.setWorldTime(this.worldInfo.getWorldTime() + 1L);
		this.theProfiler.endStartSection("tickPending");
		this.tickUpdates(false);
		this.theProfiler.endStartSection("tickTiles");
		this.tickBlocksAndAmbiance();
		this.theProfiler.endStartSection("chunkMap");
		this.thePlayerManager.updatePlayerInstances();
		this.theProfiler.endStartSection("village");
		this.villageCollectionObj.tick();
		this.villageSiegeObj.tick();
		this.theProfiler.endStartSection("portalForcer");
		this.field_85177_Q.func_85189_a(this.getTotalWorldTime());
		for (Teleporter tele : customTeleporters) {
			tele.func_85189_a(getTotalWorldTime());
		}
		this.theProfiler.endSection();
		this.sendAndApplyBlockEvents();
	}

	@Override
	protected void tickBlocksAndAmbiance() {
		TickThreading tickThreading = TickThreading.instance;
		boolean concurrentTicks = tickThreading.enableChunkTickThreading && !mcServer.theProfiler.profilingEnabled;

		if (concurrentTicks) {
			threadManager.waitForCompletion();
		}

		chunkCoordIterator = this.activeChunkSet.iterator();

		if (concurrentTicks) {
			for (int i = 0; i < threadManager.size(); i++) {
				threadManager.run(this);
			}
		} else {
			run();
		}
	}

	@Override
	public void run() {
		double tpsFactor = MinecraftServer.getTPS() / MinecraftServer.getTargetTPS();
		final Random rand = randoms.get();
		// We use a random per thread - randoms are threadsafe, however synchronization is involved.
		// This reduces contention -> slightly increased performance, woo! :P
		while (true) {
			ChunkCoordIntPair var4;
			synchronized (chunkCoordIterator) {
				if (!chunkCoordIterator.hasNext()) {
					break;
				}
				try {
					var4 = (ChunkCoordIntPair) chunkCoordIterator.next();
				} catch (ConcurrentModificationException e) {
					break;
				}
			}

			int cX = var4.chunkXPos;
			int cZ = var4.chunkZPos;
			if ((tpsFactor < 1 && rand.nextFloat() > tpsFactor) || this.theChunkProviderServer.getChunksToUnloadSet().contains(ChunkCoordIntPair.chunkXZ2Int(cX, cZ))) {
				continue;
			}

			int xPos = cX * 16;
			int zPos = cZ * 16;
			Chunk chunk = this.theChunkProviderServer.getChunkIfExists(cX, cZ);
			if (chunk == null) {
				continue;
			}
			this.moodSoundAndLightCheck(xPos, zPos, chunk);
			theProfiler.endStartSection("chunkTick"); // endStart as moodSoundAndLightCheck starts a section.
			chunk.updateSkylight();
			int var8;
			int var9;
			int var10;
			int var11;

			theProfiler.startSection("lightning");
			if (provider.canDoLightning(chunk) && rand.nextInt(100000) == 0 && this.isRaining() && this.isThundering()) {
				this.updateLCG = this.updateLCG * 3 + 1013904223;
				var8 = this.updateLCG >> 2;
				var9 = xPos + (var8 & 15);
				var10 = zPos + (var8 >> 8 & 15);
				var11 = this.getPrecipitationHeight(var9, var10);

				if (this.canLightningStrikeAt(var9, var11, var10)) {
					this.addWeatherEffect(new EntityLightningBolt(this, (double) var9, (double) var11, (double) var10));
				}
			}

			int var13;

			theProfiler.endStartSection("precipitation");
			if (provider.canDoRainSnowIce(chunk) && rand.nextInt(16) == 0) {
				this.updateLCG = this.updateLCG * 3 + 1013904223;
				var8 = this.updateLCG >> 2;
				var9 = var8 & 15;
				var10 = var8 >> 8 & 15;
				var11 = this.getPrecipitationHeight(var9 + xPos, var10 + zPos);

				if (this.isBlockFreezableNaturally(var9 + xPos, var11 - 1, var10 + zPos)) {
					this.setBlockWithNotify(var9 + xPos, var11 - 1, var10 + zPos, Block.ice.blockID);
				}

				if (this.isRaining() && this.canSnowAt(var9 + xPos, var11, var10 + zPos)) {
					this.setBlockWithNotify(var9 + xPos, var11, var10 + zPos, Block.snow.blockID);
				}

				if (this.isRaining()) {
					BiomeGenBase var12 = this.getBiomeGenForCoords(var9 + xPos, var10 + zPos);

					if (var12.canSpawnLightningBolt()) {
						var13 = this.getBlockId(var9 + xPos, var11 - 1, var10 + zPos);

						if (var13 != 0) {
							Block.blocksList[var13].fillWithRain(this, var9 + xPos, var11 - 1, var10 + zPos);
						}
					}
				}
			}

			theProfiler.endStartSection("blockTick");
			ExtendedBlockStorage[] var19 = chunk.getBlockStorageArray();
			var9 = var19.length;

			for (var10 = 0; var10 < var9; ++var10) {
				ExtendedBlockStorage var21 = var19[var10];

				if (var21 != null && var21.getNeedsRandomTick()) {
					for (int var20 = 0; var20 < 3; ++var20) {
						this.updateLCG = this.updateLCG * 3 + 1013904223;
						var13 = this.updateLCG >> 2;
						int var14 = var13 & 15;
						int var15 = var13 >> 8 & 15;
						int var16 = var13 >> 16 & 15;
						int var17 = var21.getExtBlockID(var14, var16, var15);
						Block var18 = Block.blocksList[var17];

						if (var18 != null && var18.getTickRandomly()) {
							try {
								var18.updateTick(this, var14 + xPos, var16 + var21.getYLocation(), var15 + zPos, rand);
							} catch (Exception e) {
								Log.severe("Exception ticking block " + var18 + " at x" + var14 + xPos + 'y' + var16 + var21.getYLocation() + 'z' + var15 + zPos, e);
							}
						}
					}
				}
			}
			theProfiler.endSection();
			theProfiler.endStartSection("iterate");
		}
	}

	public static class ThreadLocalRandom extends ThreadLocal<Random> {
		@Override
		public Random initialValue() {
			return new Random();
		}
	}
}
