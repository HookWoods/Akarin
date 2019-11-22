package com.destroystokyo.paper.antixray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.destroystokyo.paper.io.PrioritizedTaskQueue;
import net.minecraft.server.*;
import org.bukkit.Bukkit;
import org.bukkit.World.Environment;

import com.destroystokyo.paper.PaperWorldConfig;

public class ChunkPacketBlockControllerAntiXray extends ChunkPacketBlockController {

    private static ExecutorService executorServiceInstance = null;
    private final ExecutorService executorService;
    private final boolean asynchronous;
    private final EngineMode engineMode;
    private final ChunkEdgeMode chunkEdgeMode;
    private final int maxChunkSectionIndex;
    private final int updateRadius;
    private final IBlockData[] predefinedBlockData;
    private final IBlockData[] predefinedBlockDataStone;
    private final IBlockData[] predefinedBlockDataNetherrack;
    private final IBlockData[] predefinedBlockDataEndStone;
    private final int[] predefinedBlockDataBitsGlobal;
    private final int[] predefinedBlockDataBitsStoneGlobal;
    private final int[] predefinedBlockDataBitsNetherrackGlobal;
    private final int[] predefinedBlockDataBitsEndStoneGlobal;
    private final boolean[] solidGlobal = new boolean[Block.REGISTRY_ID.size()];
    private final boolean[] obfuscateGlobal = new boolean[Block.REGISTRY_ID.size()];
    private final ChunkSection[] emptyNearbyChunkSections = {Chunk.EMPTY_CHUNK_SECTION, Chunk.EMPTY_CHUNK_SECTION, Chunk.EMPTY_CHUNK_SECTION, Chunk.EMPTY_CHUNK_SECTION};
    private final int maxBlockYUpdatePosition;

    public ChunkPacketBlockControllerAntiXray(PaperWorldConfig paperWorldConfig) {
        asynchronous = paperWorldConfig.asynchronous;
        engineMode = paperWorldConfig.engineMode;
        chunkEdgeMode = paperWorldConfig.chunkEdgeMode;
        maxChunkSectionIndex = paperWorldConfig.maxChunkSectionIndex;
        updateRadius = paperWorldConfig.updateRadius;

        if (asynchronous) {
            executorService = getExecutorServiceInstance();
        } else {
            executorService = null;
        }

        List<String> toObfuscate;

        if (engineMode == EngineMode.HIDE) {
            toObfuscate = paperWorldConfig.hiddenBlocks;
            predefinedBlockData = null;
            predefinedBlockDataStone = new IBlockData[] {Blocks.STONE.getBlockData()};
            predefinedBlockDataNetherrack = new IBlockData[] {Blocks.NETHERRACK.getBlockData()};
            predefinedBlockDataEndStone = new IBlockData[] {Blocks.END_STONE.getBlockData()};
            predefinedBlockDataBitsGlobal = null;
            predefinedBlockDataBitsStoneGlobal = new int[] {ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(Blocks.STONE.getBlockData())};
            predefinedBlockDataBitsNetherrackGlobal = new int[] {ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(Blocks.NETHERRACK.getBlockData())};
            predefinedBlockDataBitsEndStoneGlobal = new int[] {ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(Blocks.END_STONE.getBlockData())};
        } else {
            toObfuscate = new ArrayList<>(paperWorldConfig.replacementBlocks);
            Set<IBlockData> predefinedBlockDataSet = new HashSet<IBlockData>();

            for (String id : paperWorldConfig.hiddenBlocks) {
                Block block = IRegistry.BLOCK.getOptional(new MinecraftKey(id)).orElse(null);

                if (block != null && !block.isTileEntity()) {
                    toObfuscate.add(id);
                    predefinedBlockDataSet.add(block.getBlockData());
                }
            }

            predefinedBlockData = predefinedBlockDataSet.size() == 0 ? new IBlockData[] {Blocks.DIAMOND_ORE.getBlockData()} : predefinedBlockDataSet.toArray(new IBlockData[0]);
            predefinedBlockDataStone = null;
            predefinedBlockDataNetherrack = null;
            predefinedBlockDataEndStone = null;
            predefinedBlockDataBitsGlobal = new int[predefinedBlockData.length];

            for (int i = 0; i < predefinedBlockData.length; i++) {
                predefinedBlockDataBitsGlobal[i] = ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(predefinedBlockData[i]);
            }

            predefinedBlockDataBitsStoneGlobal = null;
            predefinedBlockDataBitsNetherrackGlobal = null;
            predefinedBlockDataBitsEndStoneGlobal = null;
        }

        for (String id : toObfuscate) {
            Block block = IRegistry.BLOCK.getOptional(new MinecraftKey(id)).orElse(null);

            if (block != null) {
                obfuscateGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(block.getBlockData())] = true;
            }
        }

        ChunkEmpty emptyChunk = new ChunkEmpty(null, new ChunkCoordIntPair(0, 0));
        BlockPosition zeroPos = new BlockPosition(0, 0, 0);

        for (int i = 0; i < solidGlobal.length; i++) {
            IBlockData blockData = ChunkSection.GLOBAL_PALETTE.getObject(i);

            if (blockData != null) {
                solidGlobal[i] = blockData.getBlock().isOccluding(blockData, emptyChunk, zeroPos)
                    && blockData.getBlock() != Blocks.SPAWNER && blockData.getBlock() != Blocks.BARRIER && blockData.getBlock() != Blocks.SHULKER_BOX;
                // shulker box checks TE.
            }
        }

        this.maxBlockYUpdatePosition = (maxChunkSectionIndex + 1) * 16 + updateRadius - 1;
    }

    private static ExecutorService getExecutorServiceInstance() {
        if (executorServiceInstance == null) {
            executorServiceInstance = Executors.newSingleThreadExecutor();
        }

        return executorServiceInstance;
    }

    @Override
    public IBlockData[] getPredefinedBlockData(IWorldReader world, IChunkAccess chunk, ChunkSection chunkSection, boolean initializeBlocks) {
        //Return the block data which should be added to the data palettes so that they can be used for the obfuscation
        if (chunkSection.getYPosition() >> 4 <= maxChunkSectionIndex) {
            switch (engineMode) {
                case HIDE:
                    if (world instanceof GeneratorAccess) {
                        switch (((GeneratorAccess) world).getMinecraftWorld().getWorld().getEnvironment()) {
                            case NETHER:
                                return predefinedBlockDataNetherrack;
                            case THE_END:
                                return predefinedBlockDataEndStone;
                            default:
                                return predefinedBlockDataStone;
                        }
                    }

                    return null;
                default:
                    return predefinedBlockData;
            }
        }

        return null;
    }

    private final AtomicInteger xrayRequests = new AtomicInteger();

    // Paper start - async chunk api
    private Integer nextTicketHold() {
        return Integer.valueOf(this.xrayRequests.getAndIncrement());
    }
    // Paper end

    private Integer addXrayTickets(final int x, final int z, final ChunkProviderServer chunkProvider) {
        final Integer hold = Integer.valueOf(this.xrayRequests.getAndIncrement());

        // Add at ticket level 33, which is just enough to keep chunks loaded
        chunkProvider.addTicket(TicketType.ANTIXRAY, new ChunkCoordIntPair(x, z), 0, hold);
        chunkProvider.addTicket(TicketType.ANTIXRAY, new ChunkCoordIntPair(x - 1, z), 0, hold);
        chunkProvider.addTicket(TicketType.ANTIXRAY, new ChunkCoordIntPair(x + 1, z), 0, hold);
        chunkProvider.addTicket(TicketType.ANTIXRAY, new ChunkCoordIntPair(x, z - 1), 0, hold);
        chunkProvider.addTicket(TicketType.ANTIXRAY, new ChunkCoordIntPair(x, z + 1), 0, hold);

        return hold;
    }

    private void removeXrayTickets(final int x, final int z, final ChunkProviderServer chunkProvider, final Integer hold) {
        // Remove at ticket level 33 (same one we added as), which is just enough to keep chunks loaded
        chunkProvider.removeTicket(TicketType.ANTIXRAY, new ChunkCoordIntPair(x, z), 0, hold);
        chunkProvider.removeTicket(TicketType.ANTIXRAY, new ChunkCoordIntPair(x - 1, z), 0, hold);
        chunkProvider.removeTicket(TicketType.ANTIXRAY, new ChunkCoordIntPair(x + 1, z), 0, hold);
        chunkProvider.removeTicket(TicketType.ANTIXRAY, new ChunkCoordIntPair(x, z - 1), 0, hold);
        chunkProvider.removeTicket(TicketType.ANTIXRAY, new ChunkCoordIntPair(x, z + 1), 0, hold);
    }

    private void loadNeighbours(Chunk chunk) {
        int locX = chunk.getPos().x;
        int locZ = chunk.getPos().z;
        chunk.world.getChunkAt(locX - 1, locZ);
        chunk.world.getChunkAt(locX + 1, locZ);
        chunk.world.getChunkAt(locX, locZ - 1);
        chunk.world.getChunkAt(locX, locZ + 1);
    }

    // Paper start - async chunk api
    private void loadNeighbourAsync(ChunkProviderServer chunkProvider, WorldServer world, int chunkX, int chunkZ, int[] counter, java.util.function.Consumer<Chunk> onNeighourLoad, Runnable onAllNeighboursLoad) {
        chunkProvider.getChunkAtAsynchronously(chunkX, chunkZ, true, (Chunk neighbour) -> {
            onNeighourLoad.accept(neighbour);
            if (++counter[0] == 4) {
                onAllNeighboursLoad.run();
            }
        });
        world.asyncChunkTaskManager.raisePriority(chunkX, chunkZ, PrioritizedTaskQueue.HIGHER_PRIORITY);
    }

    private void loadNeighboursAsync(Chunk chunk, java.util.function.Consumer<Chunk> onNeighourLoad, Runnable onAllNeighboursLoad) {
        int[] loaded = new int[1];

        int locX = chunk.getPos().x;
        int locZ = chunk.getPos().z;
        WorldServer world = ((WorldServer)chunk.world);

        onNeighourLoad.accept(chunk);

        ChunkProviderServer chunkProvider = world.getChunkProvider();

        this.loadNeighbourAsync(chunkProvider, world, locX - 1, locZ, loaded, onNeighourLoad, onAllNeighboursLoad);
        this.loadNeighbourAsync(chunkProvider, world, locX + 1, locZ, loaded, onNeighourLoad, onAllNeighboursLoad);
        this.loadNeighbourAsync(chunkProvider, world, locX, locZ - 1, loaded, onNeighourLoad, onAllNeighboursLoad);
        this.loadNeighbourAsync(chunkProvider, world, locX, locZ + 1, loaded, onNeighourLoad, onAllNeighboursLoad);
    }
    // Paper end

    @Override
    public boolean onChunkPacketCreate(Chunk chunk, int chunkSectionSelector, boolean force) {
        int locX = chunk.getPos().x;
        int locZ = chunk.getPos().z;
        WorldServer world = (WorldServer)chunk.world;
        ChunkProviderServer chunkProvider = world.getChunkProvider();

        //Load nearby chunks if necessary
        if (force || chunkEdgeMode == ChunkEdgeMode.LOAD) { // TODO temporary
            // if forced, load NOW;
            this.loadNeighbours(chunk);
        } else if (chunkEdgeMode == ChunkEdgeMode.WAIT) {
            if (chunkProvider.getChunkAtIfCachedImmediately(locX - 1, locZ) == null ||
                chunkProvider.getChunkAtIfCachedImmediately(locX + 1, locZ) == null ||
                chunkProvider.getChunkAtIfCachedImmediately(locX, locZ - 1) == null ||
                chunkProvider.getChunkAtIfCachedImmediately(locX, locZ + 1) == null) {
                //Don't create the chunk packet now, wait until nearby chunks are loaded and create it later
                return false;
            }
        } else if (false && chunkEdgeMode == ChunkEdgeMode.LOAD) {
            // TODO Note: These should be asynchronous loads; however we have no such thing in 1.14.
            boolean missingChunk = false;
            //noinspection ConstantConditions
            /*
            missingChunk |= ((WorldServer)chunk.world).getChunkProvider().getChunkAt(chunk.locX - 1, chunk.locZ, true, true, c -> {}) == null;
            missingChunk |= ((WorldServer)chunk.world).getChunkProvider().getChunkAt(chunk.locX + 1, chunk.locZ, true, true, c -> {}) == null;
            missingChunk |= ((WorldServer)chunk.world).getChunkProvider().getChunkAt(chunk.locX, chunk.locZ - 1, true, true, c -> {}) == null;
            missingChunk |= ((WorldServer)chunk.world).getChunkProvider().getChunkAt(chunk.locX, chunk.locZ + 1, true, true, c -> {}) == null;
             */
            if (missingChunk) {
                return false;
            }
        }

        //Create the chunk packet now
        return true;
    }

    @Override
    public ChunkPacketInfoAntiXray getChunkPacketInfo(PacketPlayOutMapChunk packetPlayOutMapChunk, Chunk chunk,
                                                      int chunkSectionSelector, boolean forceLoad) {
        // Return a new instance to collect data and objects in the right state while creating the chunk packet for thread safe access later
        // Note: As of 1.14 this has to be moved later due to the chunk system.

        ChunkPacketInfoAntiXray chunkPacketInfoAntiXray = new ChunkPacketInfoAntiXray(packetPlayOutMapChunk, chunk, chunkSectionSelector, this);
        return chunkPacketInfoAntiXray;
    }

    @Override
    public void modifyBlocks(PacketPlayOutMapChunk packetPlayOutMapChunk, ChunkPacketInfo<IBlockData> chunkPacketInfo, boolean loadChunks, Integer hold) {
        if (!Bukkit.isPrimaryThread()) {
            // plugins?
            final Integer finalHold = hold;
            MinecraftServer.getServer().scheduleOnMain(() -> {
                this.modifyBlocks(packetPlayOutMapChunk, chunkPacketInfo, loadChunks, finalHold);
            });
            return;
        }
        Chunk chunk = chunkPacketInfo.getChunk();
        int locX = chunk.getPos().x;
        int locZ = chunk.getPos().z;
        WorldServer world = (WorldServer)chunk.world;

        Chunk[] chunks = new Chunk[] {
            (Chunk)world.getChunkIfLoadedImmediately(locX - 1, locZ),
            (Chunk)world.getChunkIfLoadedImmediately(locX + 1, locZ),
            (Chunk)world.getChunkIfLoadedImmediately(locX, locZ - 1),
            (Chunk)world.getChunkIfLoadedImmediately(locX, locZ + 1)
        };

        if (loadChunks) {
            // Note: This ugly hack is to get us out of the general chunk load/unload queue to prevent deadlock

            if (chunks[0] == null || chunks[1] == null || chunks[2] == null || chunks[3] == null) {
                // we need to load
                // Paper start - async chunk api
                Integer ticketHold = this.nextTicketHold();
                this.loadNeighboursAsync(chunk, (Chunk neighbour) -> { // when a neighbour is loaded
                        ((WorldServer)neighbour.world).getChunkProvider().addTicket(TicketType.ANTIXRAY, neighbour.getPos(), 0, ticketHold);
                },
                () -> { // once neighbours get loaded
                    this.modifyBlocks(packetPlayOutMapChunk, chunkPacketInfo, false, ticketHold);
                });
                // Paper end
                return;
            }

            hold = this.addXrayTickets(locX, locZ, world.getChunkProvider());
            // fall through to normal behavior, our chunks are now loaded & have a ticket
        }

        ((ChunkPacketInfoAntiXray)chunkPacketInfo).setNearbyChunks(chunks);
        ((ChunkPacketInfoAntiXray)chunkPacketInfo).ticketHold = hold;

        if (asynchronous) {
            executorService.submit((ChunkPacketInfoAntiXray) chunkPacketInfo);
        } else {
            obfuscate((ChunkPacketInfoAntiXray) chunkPacketInfo);
        }
    }

    //Actually these fields should be variables inside the obfuscate method but in sync mode or with SingleThreadExecutor in async mode it's okay
    private int[] predefinedBlockDataBits;
    private final boolean[] solid = new boolean[Block.REGISTRY_ID.size()];
    private final boolean[] obfuscate = new boolean[Block.REGISTRY_ID.size()];
    //These boolean arrays represent chunk layers, true means don't obfuscate, false means obfuscate
    private boolean[][] current = new boolean[16][16];
    private boolean[][] next = new boolean[16][16];
    private boolean[][] nextNext = new boolean[16][16];
    private final DataBitsReader dataBitsReader = new DataBitsReader();
    private final DataBitsWriter dataBitsWriter = new DataBitsWriter();
    private final ChunkSection[] nearbyChunkSections = new ChunkSection[4];

    public void obfuscate(ChunkPacketInfoAntiXray chunkPacketInfoAntiXray) {
        try {
            boolean[] solidTemp = null;
            boolean[] obfuscateTemp = null;
            dataBitsReader.setDataBits(chunkPacketInfoAntiXray.getData());
            dataBitsWriter.setDataBits(chunkPacketInfoAntiXray.getData());
            int counter = 0;

            for (int chunkSectionIndex = 0; chunkSectionIndex <= maxChunkSectionIndex; chunkSectionIndex++) {
                if (chunkPacketInfoAntiXray.isWritten(chunkSectionIndex) && chunkPacketInfoAntiXray.getPredefinedObjects(chunkSectionIndex) != null) {
                    int[] predefinedBlockDataBitsTemp;

                    if (chunkPacketInfoAntiXray.getDataPalette(chunkSectionIndex) == ChunkSection.GLOBAL_PALETTE) {
                        predefinedBlockDataBitsTemp = engineMode == EngineMode.HIDE ? chunkPacketInfoAntiXray.getChunk().world.getWorld().getEnvironment() == Environment.NETHER ? predefinedBlockDataBitsNetherrackGlobal : chunkPacketInfoAntiXray.getChunk().world.getWorld().getEnvironment() == Environment.THE_END ? predefinedBlockDataBitsEndStoneGlobal : predefinedBlockDataBitsStoneGlobal : predefinedBlockDataBitsGlobal;
                    } else {
                        predefinedBlockDataBitsTemp = predefinedBlockDataBits == null ? predefinedBlockDataBits = engineMode == EngineMode.HIDE ? new int[1] : new int[predefinedBlockData.length] : predefinedBlockDataBits;

                        for (int i = 0; i < predefinedBlockDataBitsTemp.length; i++) {
                            predefinedBlockDataBitsTemp[i] = chunkPacketInfoAntiXray.getDataPalette(chunkSectionIndex).getOrCreateIdFor(chunkPacketInfoAntiXray.getPredefinedObjects(chunkSectionIndex)[i]);
                        }
                    }

                    dataBitsWriter.setIndex(chunkPacketInfoAntiXray.getOrCreateIdForIndex(chunkSectionIndex));

                    //Check if the chunk section below was not obfuscated
                    if (chunkSectionIndex == 0 || !chunkPacketInfoAntiXray.isWritten(chunkSectionIndex - 1) || chunkPacketInfoAntiXray.getPredefinedObjects(chunkSectionIndex - 1) == null) {
                        //If so, initialize some stuff
                        dataBitsReader.setBitsPerObject(chunkPacketInfoAntiXray.getBitsPerObject(chunkSectionIndex));
                        dataBitsReader.setIndex(chunkPacketInfoAntiXray.getOrCreateIdForIndex(chunkSectionIndex));
                        solidTemp = readDataPalette(chunkPacketInfoAntiXray.getDataPalette(chunkSectionIndex), solid, solidGlobal);
                        obfuscateTemp = readDataPalette(chunkPacketInfoAntiXray.getDataPalette(chunkSectionIndex), obfuscate, obfuscateGlobal);
                        //Read the blocks of the upper layer of the chunk section below if it exists
                        ChunkSection belowChunkSection = null;
                        boolean skipFirstLayer = chunkSectionIndex == 0 || (belowChunkSection = chunkPacketInfoAntiXray.getChunk().getSections()[chunkSectionIndex - 1]) == Chunk.EMPTY_CHUNK_SECTION;

                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                current[z][x] = true;
                                next[z][x] = skipFirstLayer || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(belowChunkSection.getType(x, 15, z))];
                            }
                        }

                        //Abuse the obfuscateLayer method to read the blocks of the first layer of the current chunk section
                        dataBitsWriter.setBitsPerObject(0);
                        obfuscateLayer(-1, dataBitsReader, dataBitsWriter, solidTemp, obfuscateTemp, predefinedBlockDataBitsTemp, current, next, nextNext, emptyNearbyChunkSections, counter);
                    }

                    dataBitsWriter.setBitsPerObject(chunkPacketInfoAntiXray.getBitsPerObject(chunkSectionIndex));
                    nearbyChunkSections[0] = chunkPacketInfoAntiXray.getNearbyChunks()[0] == null ? Chunk.EMPTY_CHUNK_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[0].getSections()[chunkSectionIndex];
                    nearbyChunkSections[1] = chunkPacketInfoAntiXray.getNearbyChunks()[1] == null ? Chunk.EMPTY_CHUNK_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[1].getSections()[chunkSectionIndex];
                    nearbyChunkSections[2] = chunkPacketInfoAntiXray.getNearbyChunks()[2] == null ? Chunk.EMPTY_CHUNK_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[2].getSections()[chunkSectionIndex];
                    nearbyChunkSections[3] = chunkPacketInfoAntiXray.getNearbyChunks()[3] == null ? Chunk.EMPTY_CHUNK_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[3].getSections()[chunkSectionIndex];

                    //Obfuscate all layers of the current chunk section except the upper one
                    for (int y = 0; y < 15; y++) {
                        boolean[][] temp = current;
                        current = next;
                        next = nextNext;
                        nextNext = temp;
                        counter = obfuscateLayer(y, dataBitsReader, dataBitsWriter, solidTemp, obfuscateTemp, predefinedBlockDataBitsTemp, current, next, nextNext, nearbyChunkSections, counter);
                    }

                    //Check if the chunk section above doesn't need obfuscation
                    if (chunkSectionIndex == maxChunkSectionIndex || !chunkPacketInfoAntiXray.isWritten(chunkSectionIndex + 1) || chunkPacketInfoAntiXray.getPredefinedObjects(chunkSectionIndex + 1) == null) {
                        //If so, obfuscate the upper layer of the current chunk section by reading blocks of the first layer from the chunk section above if it exists
                        ChunkSection aboveChunkSection;

                        if (chunkSectionIndex != 15 && (aboveChunkSection = chunkPacketInfoAntiXray.getChunk().getSections()[chunkSectionIndex + 1]) != Chunk.EMPTY_CHUNK_SECTION) {
                            boolean[][] temp = current;
                            current = next;
                            next = nextNext;
                            nextNext = temp;

                            for (int z = 0; z < 16; z++) {
                                for (int x = 0; x < 16; x++) {
                                    if (!solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(aboveChunkSection.getType(x, 0, z))]) {
                                        current[z][x] = true;
                                    }
                                }
                            }

                            //There is nothing to read anymore
                            dataBitsReader.setBitsPerObject(0);
                            solid[0] = true;
                            counter = obfuscateLayer(15, dataBitsReader, dataBitsWriter, solid, obfuscateTemp, predefinedBlockDataBitsTemp, current, next, nextNext, nearbyChunkSections, counter);
                        }
                    } else {
                        //If not, initialize the reader and other stuff for the chunk section above to obfuscate the upper layer of the current chunk section
                        dataBitsReader.setBitsPerObject(chunkPacketInfoAntiXray.getBitsPerObject(chunkSectionIndex + 1));
                        dataBitsReader.setIndex(chunkPacketInfoAntiXray.getOrCreateIdForIndex(chunkSectionIndex + 1));
                        solidTemp = readDataPalette(chunkPacketInfoAntiXray.getDataPalette(chunkSectionIndex + 1), solid, solidGlobal);
                        obfuscateTemp = readDataPalette(chunkPacketInfoAntiXray.getDataPalette(chunkSectionIndex + 1), obfuscate, obfuscateGlobal);
                        boolean[][] temp = current;
                        current = next;
                        next = nextNext;
                        nextNext = temp;
                        counter = obfuscateLayer(15, dataBitsReader, dataBitsWriter, solidTemp, obfuscateTemp, predefinedBlockDataBitsTemp, current, next, nextNext, nearbyChunkSections, counter);
                    }

                    dataBitsWriter.finish();
                }
            }

            chunkPacketInfoAntiXray.getPacketPlayOutMapChunk().setReady(true);

        } finally {
            if (chunkPacketInfoAntiXray.ticketHold != null) {
                Runnable runnable = () -> {
                    Chunk chunk = chunkPacketInfoAntiXray.getChunk();
                    ChunkCoordIntPair chunkPos = chunk.getPos();

                    ChunkPacketBlockControllerAntiXray.this.removeXrayTickets(chunkPos.x, chunkPos.z, (ChunkProviderServer) chunk.world.getChunkProvider(),
                        chunkPacketInfoAntiXray.ticketHold);
                };
                if (MinecraftServer.getServer().isMainThread()) {
                    runnable.run();
                } else {
                    MinecraftServer.getServer().scheduleOnMain(runnable);
                }
            }
        }
    }

    private int obfuscateLayer(int y, DataBitsReader dataBitsReader, DataBitsWriter dataBitsWriter, boolean[] solid, boolean[] obfuscate, int[] predefinedBlockDataBits, boolean[][] current, boolean[][] next, boolean[][] nextNext, ChunkSection[] nearbyChunkSections, int counter) {
        //First block of first line
        int dataBits = dataBitsReader.read();

        if (nextNext[0][0] = !solid[dataBits]) {
            dataBitsWriter.skip();
            next[0][1] = true;
            next[1][0] = true;
        } else {
            if (nearbyChunkSections[2] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[2].getType(0, y, 15))] || nearbyChunkSections[0] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[0].getType(15, y, 0))] || current[0][0]) {
                dataBitsWriter.skip();
            } else {
                if (counter >= predefinedBlockDataBits.length) {
                    counter = 0;
                }

                dataBitsWriter.write(predefinedBlockDataBits[counter++]);
            }
        }

        if (!obfuscate[dataBits]) {
            next[0][0] = true;
        }

        //First line
        for (int x = 1; x < 15; x++) {
            dataBits = dataBitsReader.read();

            if (nextNext[0][x] = !solid[dataBits]) {
                dataBitsWriter.skip();
                next[0][x - 1] = true;
                next[0][x + 1] = true;
                next[1][x] = true;
            } else {
                if (nearbyChunkSections[2] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[2].getType(x, y, 15))] || current[0][x]) {
                    dataBitsWriter.skip();
                } else {
                    if (counter >= predefinedBlockDataBits.length) {
                        counter = 0;
                    }

                    dataBitsWriter.write(predefinedBlockDataBits[counter++]);
                }
            }

            if (!obfuscate[dataBits]) {
                next[0][x] = true;
            }
        }

        //Last block of first line
        dataBits = dataBitsReader.read();

        if (nextNext[0][15] = !solid[dataBits]) {
            dataBitsWriter.skip();
            next[0][14] = true;
            next[1][15] = true;
        } else {
            if (nearbyChunkSections[2] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[2].getType(15, y, 15))] || nearbyChunkSections[1] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[1].getType(0, y, 0))] || current[0][15]) {
                dataBitsWriter.skip();
            } else {
                if (counter >= predefinedBlockDataBits.length) {
                    counter = 0;
                }

                dataBitsWriter.write(predefinedBlockDataBits[counter++]);
            }
        }

        if (!obfuscate[dataBits]) {
            next[0][15] = true;
        }

        //All inner lines
        for (int z = 1; z < 15; z++) {
            //First block
            dataBits = dataBitsReader.read();

            if (nextNext[z][0] = !solid[dataBits]) {
                dataBitsWriter.skip();
                next[z][1] = true;
                next[z - 1][0] = true;
                next[z + 1][0] = true;
            } else {
                if (nearbyChunkSections[0] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[0].getType(15, y, z))] || current[z][0]) {
                    dataBitsWriter.skip();
                } else {
                    if (counter >= predefinedBlockDataBits.length) {
                        counter = 0;
                    }

                    dataBitsWriter.write(predefinedBlockDataBits[counter++]);
                }
            }

            if (!obfuscate[dataBits]) {
                next[z][0] = true;
            }

            //All inner blocks
            for (int x = 1; x < 15; x++) {
                dataBits = dataBitsReader.read();

                if (nextNext[z][x] = !solid[dataBits]) {
                    dataBitsWriter.skip();
                    next[z][x - 1] = true;
                    next[z][x + 1] = true;
                    next[z - 1][x] = true;
                    next[z + 1][x] = true;
                } else {
                    if (current[z][x]) {
                        dataBitsWriter.skip();
                    } else {
                        if (counter >= predefinedBlockDataBits.length) {
                            counter = 0;
                        }

                        dataBitsWriter.write(predefinedBlockDataBits[counter++]);
                    }
                }

                if (!obfuscate[dataBits]) {
                    next[z][x] = true;
                }
            }

            //Last block
            dataBits = dataBitsReader.read();

            if (nextNext[z][15] = !solid[dataBits]) {
                dataBitsWriter.skip();
                next[z][14] = true;
                next[z - 1][15] = true;
                next[z + 1][15] = true;
            } else {
                if (nearbyChunkSections[1] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[1].getType(0, y, z))] || current[z][15]) {
                    dataBitsWriter.skip();
                } else {
                    if (counter >= predefinedBlockDataBits.length) {
                        counter = 0;
                    }

                    dataBitsWriter.write(predefinedBlockDataBits[counter++]);
                }
            }

            if (!obfuscate[dataBits]) {
                next[z][15] = true;
            }
        }

        //First block of last line
        dataBits = dataBitsReader.read();

        if (nextNext[15][0] = !solid[dataBits]) {
            dataBitsWriter.skip();
            next[15][1] = true;
            next[14][0] = true;
        } else {
            if (nearbyChunkSections[3] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[3].getType(0, y, 0))] || nearbyChunkSections[0] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[0].getType(15, y, 15))] || current[15][0]) {
                dataBitsWriter.skip();
            } else {
                if (counter >= predefinedBlockDataBits.length) {
                    counter = 0;
                }

                dataBitsWriter.write(predefinedBlockDataBits[counter++]);
            }
        }

        if (!obfuscate[dataBits]) {
            next[15][0] = true;
        }

        //Last line
        for (int x = 1; x < 15; x++) {
            dataBits = dataBitsReader.read();

            if (nextNext[15][x] = !solid[dataBits]) {
                dataBitsWriter.skip();
                next[15][x - 1] = true;
                next[15][x + 1] = true;
                next[14][x] = true;
            } else {
                if (nearbyChunkSections[3] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[3].getType(x, y, 0))] || current[15][x]) {
                    dataBitsWriter.skip();
                } else {
                    if (counter >= predefinedBlockDataBits.length) {
                        counter = 0;
                    }

                    dataBitsWriter.write(predefinedBlockDataBits[counter++]);
                }
            }

            if (!obfuscate[dataBits]) {
                next[15][x] = true;
            }
        }

        //Last block of last line
        dataBits = dataBitsReader.read();

        if (nextNext[15][15] = !solid[dataBits]) {
            dataBitsWriter.skip();
            next[15][14] = true;
            next[14][15] = true;
        } else {
            if (nearbyChunkSections[3] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[3].getType(15, y, 0))] || nearbyChunkSections[1] == Chunk.EMPTY_CHUNK_SECTION || !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(nearbyChunkSections[1].getType(0, y, 15))] || current[15][15]) {
                dataBitsWriter.skip();
            } else {
                if (counter >= predefinedBlockDataBits.length) {
                    counter = 0;
                }

                dataBitsWriter.write(predefinedBlockDataBits[counter++]);
            }
        }

        if (!obfuscate[dataBits]) {
            next[15][15] = true;
        }

        return counter;
    }

    private boolean[] readDataPalette(DataPalette<IBlockData> dataPalette, boolean[] temp, boolean[] global) {
        if (dataPalette == ChunkSection.GLOBAL_PALETTE) {
            return global;
        }

        IBlockData blockData;

        for (int i = 0; (blockData = dataPalette.getObject(i)) != null; i++) {
            temp[i] = global[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(blockData)];
        }

        return temp;
    }

    @Override
    public void onBlockChange(World world, BlockPosition blockPosition, IBlockData newBlockData, IBlockData oldBlockData, int flag) {
        if (oldBlockData != null && solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(oldBlockData)] && !solidGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(newBlockData)] && blockPosition.getY() <= maxBlockYUpdatePosition) {
            updateNearbyBlocks(world, blockPosition);
        }
    }

    @Override
    public void onPlayerLeftClickBlock(PlayerInteractManager playerInteractManager, BlockPosition blockPosition, EnumDirection enumDirection) {
        if (blockPosition.getY() <= maxBlockYUpdatePosition) {
            updateNearbyBlocks(playerInteractManager.world, blockPosition);
        }
    }

    private void updateNearbyBlocks(World world, BlockPosition blockPosition) {
        if (updateRadius >= 2) {
            BlockPosition temp = blockPosition.west();
            updateBlock(world, temp);
            updateBlock(world, temp.west());
            updateBlock(world, temp.down());
            updateBlock(world, temp.up());
            updateBlock(world, temp.north());
            updateBlock(world, temp.south());
            updateBlock(world, temp = blockPosition.east());
            updateBlock(world, temp.east());
            updateBlock(world, temp.down());
            updateBlock(world, temp.up());
            updateBlock(world, temp.north());
            updateBlock(world, temp.south());
            updateBlock(world, temp = blockPosition.down());
            updateBlock(world, temp.down());
            updateBlock(world, temp.north());
            updateBlock(world, temp.south());
            updateBlock(world, temp = blockPosition.up());
            updateBlock(world, temp.up());
            updateBlock(world, temp.north());
            updateBlock(world, temp.south());
            updateBlock(world, temp = blockPosition.north());
            updateBlock(world, temp.north());
            updateBlock(world, temp = blockPosition.south());
            updateBlock(world, temp.south());
        } else if (updateRadius == 1) {
            updateBlock(world, blockPosition.west());
            updateBlock(world, blockPosition.east());
            updateBlock(world, blockPosition.down());
            updateBlock(world, blockPosition.up());
            updateBlock(world, blockPosition.north());
            updateBlock(world, blockPosition.south());
        } else {
            //Do nothing if updateRadius <= 0 (test mode)
        }
    }

    private void updateBlock(World world, BlockPosition blockPosition) {
        IBlockData blockData = world.getTypeIfLoaded(blockPosition);

        if (blockData != null && obfuscateGlobal[ChunkSection.GLOBAL_PALETTE.getOrCreateIdFor(blockData)]) {
            //world.notify(blockPosition, blockData, blockData, 3);
            ((WorldServer)world).getChunkProvider().flagDirty(blockPosition); // We only need to re-send to client
        }
    }

    public enum EngineMode {

        HIDE(1, "hide ores"),
        OBFUSCATE(2, "obfuscate");

        private final int id;
        private final String description;

        EngineMode(int id, String description) {
            this.id = id;
            this.description = description;
        }

        public static EngineMode getById(int id) {
            for (EngineMode engineMode : values()) {
                if (engineMode.id == id) {
                    return engineMode;
                }
            }

            return null;
        }

        public int getId() {
            return id;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum ChunkEdgeMode {

        DEFAULT(1, "default"),
        WAIT(2, "wait until nearby chunks are loaded"),
        LOAD(3, "load nearby chunks");

        private final int id;
        private final String description;

        ChunkEdgeMode(int id, String description) {
            this.id = id;
            this.description = description;
        }

        public static ChunkEdgeMode getById(int id) {
            for (ChunkEdgeMode chunkEdgeMode : values()) {
                if (chunkEdgeMode.id == id) {
                    return chunkEdgeMode;
                }
            }

            return null;
        }

        public int getId() {
            return id;
        }

        public String getDescription() {
            return description;
        }
    }
}
