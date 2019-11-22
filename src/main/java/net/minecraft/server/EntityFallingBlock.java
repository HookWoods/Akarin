package net.minecraft.server;

import com.google.common.collect.Lists;
import java.util.Iterator;
import java.util.List;

import org.bukkit.craftbukkit.event.CraftEventFactory; // CraftBukkit

public class EntityFallingBlock extends Entity {

    private IBlockData block;
    public int ticksLived;
    public boolean dropItem;
    private boolean g;
    public boolean hurtEntities;
    private int fallHurtMax;
    private float fallHurtAmount;
    public NBTTagCompound tileEntityData;
    protected static final DataWatcherObject<BlockPosition> e = DataWatcher.a(EntityFallingBlock.class, DataWatcherRegistry.l);

    public EntityFallingBlock(EntityTypes<? extends EntityFallingBlock> entitytypes, World world) {
        super(entitytypes, world);
        this.block = Blocks.SAND.getBlockData();
        this.dropItem = true;
        this.fallHurtMax = 40;
        this.fallHurtAmount = 2.0F;
    }

    public EntityFallingBlock(World world, double d0, double d1, double d2, IBlockData iblockdata) {
        this(EntityTypes.FALLING_BLOCK, world);
        this.block = iblockdata;
        this.i = true;
        this.setPosition(d0, d1 + (double) ((1.0F - this.getHeight()) / 2.0F), d2);
        this.setMot(Vec3D.a);
        this.lastX = d0;
        this.lastY = d1;
        this.lastZ = d2;
        this.a(new BlockPosition(this));
    }

    @Override
    public boolean bs() {
        return false;
    }

    public void a(BlockPosition blockposition) {
        this.datawatcher.set(EntityFallingBlock.e, blockposition);
    }

    @Override
    protected boolean playStepSound() {
        return false;
    }

    @Override
    protected void initDatawatcher() {
        this.datawatcher.register(EntityFallingBlock.e, BlockPosition.ZERO);
    }

    @Override
    public boolean isInteractable() {
        return !this.dead;
    }

    @Override
    public void tick() {
        if (this.block.isAir()) {
            this.die();
        } else {
            this.lastX = this.locX;
            this.lastY = this.locY;
            this.lastZ = this.locZ;
            Block block = this.block.getBlock();
            BlockPosition blockposition;

            if (this.ticksLived++ == 0) {
                blockposition = new BlockPosition(this);
                if (this.world.getType(blockposition).getBlock() == block && !CraftEventFactory.callEntityChangeBlockEvent(this, blockposition, Blocks.AIR.getBlockData()).isCancelled()) {
                    this.world.a(blockposition, false);
                } else if (!this.world.isClientSide) {
                    this.die();
                    return;
                }
            }

            if (!this.isNoGravity()) {
                this.setMot(this.getMot().add(0.0D, -0.04D, 0.0D));
            }

            this.move(EnumMoveType.SELF, this.getMot());

            // Paper start - Configurable EntityFallingBlock height nerf
            if (this.world.paperConfig.fallingBlockHeightNerf != 0 && this.locY > this.world.paperConfig.fallingBlockHeightNerf) {
                if (this.dropItem && this.world.getGameRules().getBoolean(GameRules.DO_ENTITY_DROPS)) {
                    this.a(block);
                }

                this.die();
            }
            // Paper end
            if (!this.world.isClientSide) {
                blockposition = new BlockPosition(this);
                boolean flag = this.block.getBlock() instanceof BlockConcretePowder;
                boolean flag1 = flag && this.world.getFluid(blockposition).a(TagsFluid.WATER);
                double d0 = this.getMot().g();

                if (flag && d0 > 1.0D) {
                    MovingObjectPositionBlock movingobjectpositionblock = this.world.rayTrace(new RayTrace(new Vec3D(this.lastX, this.lastY, this.lastZ), new Vec3D(this.locX, this.locY, this.locZ), RayTrace.BlockCollisionOption.COLLIDER, RayTrace.FluidCollisionOption.SOURCE_ONLY, this));

                    if (movingobjectpositionblock.getType() != MovingObjectPosition.EnumMovingObjectType.MISS && this.world.getFluid(movingobjectpositionblock.getBlockPosition()).a(TagsFluid.WATER)) {
                        blockposition = movingobjectpositionblock.getBlockPosition();
                        flag1 = true;
                    }
                }

                if (!this.onGround && !flag1) {
                    if (!this.world.isClientSide && (this.ticksLived > 100 && (blockposition.getY() < 1 || blockposition.getY() > 256) || this.ticksLived > 600)) {
                        if (this.dropItem && this.world.getGameRules().getBoolean(GameRules.DO_ENTITY_DROPS)) {
                            this.a((IMaterial) block);
                        }

                        this.die();
                    }
                } else {
                    IBlockData iblockdata = this.world.getType(blockposition);

                    this.setMot(this.getMot().d(0.7D, -0.5D, 0.7D));
                    if (iblockdata.getBlock() != Blocks.MOVING_PISTON) {
                        this.die();
                        if (!this.g) {
                            boolean flag2 = iblockdata.a((BlockActionContext) (new BlockActionContextDirectional(this.world, blockposition, EnumDirection.DOWN, ItemStack.a, EnumDirection.UP)));
                            boolean flag3 = this.block.canPlace(this.world, blockposition);

                            if (flag2 && flag3) {
                                if (this.block.b((IBlockState) BlockProperties.C) && this.world.getFluid(blockposition).getType() == FluidTypes.WATER) {
                                    this.block = (IBlockData) this.block.set(BlockProperties.C, true);
                                }

                                // CraftBukkit start
                                if (CraftEventFactory.callEntityChangeBlockEvent(this, blockposition, this.block).isCancelled()) {
                                    return;
                                }
                                // CraftBukkit end
                                if (this.world.setTypeAndData(blockposition, this.block, 3)) {
                                    if (block instanceof BlockFalling) {
                                        ((BlockFalling) block).a(this.world, blockposition, this.block, iblockdata);
                                    }

                                    if (this.tileEntityData != null && block instanceof ITileEntity) {
                                        TileEntity tileentity = this.world.getTileEntity(blockposition);

                                        if (tileentity != null) {
                                            NBTTagCompound nbttagcompound = tileentity.save(new NBTTagCompound());
                                            Iterator iterator = this.tileEntityData.getKeys().iterator();

                                            while (iterator.hasNext()) {
                                                String s = (String) iterator.next();
                                                NBTBase nbtbase = this.tileEntityData.get(s);

                                                if (!"x".equals(s) && !"y".equals(s) && !"z".equals(s)) {
                                                    nbttagcompound.set(s, nbtbase.clone());
                                                }
                                            }

                                            tileentity.load(nbttagcompound);
                                            tileentity.update();
                                        }
                                    }
                                } else if (this.dropItem && this.world.getGameRules().getBoolean(GameRules.DO_ENTITY_DROPS)) {
                                    this.a((IMaterial) block);
                                }
                            } else if (this.dropItem && this.world.getGameRules().getBoolean(GameRules.DO_ENTITY_DROPS)) {
                                this.a((IMaterial) block);
                            }
                        } else if (block instanceof BlockFalling) {
                            ((BlockFalling) block).a(this.world, blockposition);
                        }
                    }
                }
            }

            this.setMot(this.getMot().a(0.98D));
        }
    }

    @Override
    public void b(float f, float f1) {
        if (this.hurtEntities) {
            int i = MathHelper.f(f - 1.0F);

            if (i > 0) {
                List<Entity> list = Lists.newArrayList(this.world.getEntities(this, this.getBoundingBox()));
                boolean flag = this.block.a(TagsBlock.ANVIL);
                DamageSource damagesource = flag ? DamageSource.ANVIL : DamageSource.FALLING_BLOCK;
                Iterator iterator = list.iterator();

                while (iterator.hasNext()) {
                    Entity entity = (Entity) iterator.next();

                    CraftEventFactory.entityDamage = this; // CraftBukkit
                    entity.damageEntity(damagesource, (float) Math.min(MathHelper.d((float) i * this.fallHurtAmount), this.fallHurtMax));
                    CraftEventFactory.entityDamage = null; // CraftBukkit
                }

                if (flag && (double) this.random.nextFloat() < 0.05000000074505806D + (double) i * 0.05D) {
                    IBlockData iblockdata = BlockAnvil.a_(this.block);

                    if (iblockdata == null) {
                        this.g = true;
                    } else {
                        this.block = iblockdata;
                    }
                }
            }
        }

    }

    @Override
    protected void b(NBTTagCompound nbttagcompound) {
        nbttagcompound.set("BlockState", GameProfileSerializer.a(this.block));
        nbttagcompound.setInt("Time", this.ticksLived);
        nbttagcompound.setBoolean("DropItem", this.dropItem);
        nbttagcompound.setBoolean("HurtEntities", this.hurtEntities);
        nbttagcompound.setFloat("FallHurtAmount", this.fallHurtAmount);
        nbttagcompound.setInt("FallHurtMax", this.fallHurtMax);
        if (this.tileEntityData != null) {
            nbttagcompound.set("TileEntityData", this.tileEntityData);
        }

    }

    @Override
    protected void a(NBTTagCompound nbttagcompound) {
        this.block = GameProfileSerializer.d(nbttagcompound.getCompound("BlockState"));

        // Paper start - Block FallingBlocks with Command Blocks
        // Check mappings on update - dc = "repeating_command_block" - dd = "chain_command_block"
        final Block b = this.block.getBlock();
        if (this.world.paperConfig.filterNBTFromSpawnEgg && (b == Blocks.COMMAND_BLOCK || b == Blocks.REPEATING_COMMAND_BLOCK || b == Blocks.CHAIN_COMMAND_BLOCK)) {
            this.block = Blocks.STONE.getBlockData();
        }
        // Paper end

        this.ticksLived = nbttagcompound.getInt("Time");
        if (nbttagcompound.hasKeyOfType("HurtEntities", 99)) {
            this.hurtEntities = nbttagcompound.getBoolean("HurtEntities");
            this.fallHurtAmount = nbttagcompound.getFloat("FallHurtAmount");
            this.fallHurtMax = nbttagcompound.getInt("FallHurtMax");
        } else if (this.block.a(TagsBlock.ANVIL)) {
            this.hurtEntities = true;
        }

        if (nbttagcompound.hasKeyOfType("DropItem", 99)) {
            this.dropItem = nbttagcompound.getBoolean("DropItem");
        }

        if (nbttagcompound.hasKeyOfType("TileEntityData", 10)) {
            this.tileEntityData = nbttagcompound.getCompound("TileEntityData");
        }

        if (this.block.isAir()) {
            this.block = Blocks.SAND.getBlockData();
        }

        // Paper start - Try and load origin location from the old NBT tags for backwards compatibility
        if (nbttagcompound.hasKey("SourceLoc_x")) {
            int srcX = nbttagcompound.getInt("SourceLoc_x");
            int srcY = nbttagcompound.getInt("SourceLoc_y");
            int srcZ = nbttagcompound.getInt("SourceLoc_z");
            origin = new org.bukkit.Location(world.getWorld(), srcX, srcY, srcZ);
        }
        // Paper end
    }

    public void a(boolean flag) {
        this.hurtEntities = flag;
    }

    @Override
    public void appendEntityCrashDetails(CrashReportSystemDetails crashreportsystemdetails) {
        super.appendEntityCrashDetails(crashreportsystemdetails);
        crashreportsystemdetails.a("Immitating BlockState", (Object) this.block.toString());
    }

    public IBlockData getBlock() {
        return this.block;
    }

    @Override
    public boolean bT() {
        return true;
    }

    @Override
    public Packet<?> N() {
        return new PacketPlayOutSpawnEntity(this, Block.getCombinedId(this.getBlock()));
    }
}
