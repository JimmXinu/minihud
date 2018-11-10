package fi.dy.masa.minihud.event;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.minihud.config.Configs;
import fi.dy.masa.minihud.config.InfoToggle;
import fi.dy.masa.minihud.mixin.IMixinRenderGlobal;
import fi.dy.masa.minihud.renderer.OverlayRenderer;
import fi.dy.masa.minihud.util.DataStorage;
import fi.dy.masa.minihud.util.MiscUtils;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;

public class RenderHandler implements IRenderer
{
    private static final RenderHandler INSTANCE = new RenderHandler();
    private final DataStorage data;
    private final Date date;
    private int fps;
    private int fpsCounter;
    private long fpsUpdateTime = Minecraft.getSystemTime();
    private long infoUpdateTime;
    private double fontScale = 0.5d;
    private Set<InfoToggle> addedTypes = new HashSet<>();

    private final List<StringHolder> lineWrappers = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();

    public RenderHandler()
    {
        this.data = DataStorage.getInstance();
        this.date = new Date();
    }

    public static RenderHandler getInstance()
    {
        return INSTANCE;
    }

    public DataStorage getDataStorage()
    {
        return this.data;
    }

    public static void fixDebugRendererState()
    {
        if (Configs.Generic.FIX_VANILLA_DEBUG_RENDERERS.getBooleanValue())
        {
            GlStateManager.disableLighting();
            //GlStateManager.color(1, 1, 1, 1);
            //OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
        }
    }

    @Override
    public void onRenderGameOverlayPost(float partialTicks)
    {
        Minecraft mc = Minecraft.getMinecraft();

        if (Configs.Generic.ENABLED.getBooleanValue() &&
            mc.gameSettings.showDebugInfo == false &&
            mc.player != null &&
            (Configs.Generic.REQUIRE_SNEAK.getBooleanValue() == false || mc.player.isSneaking()) &&
            (Configs.Generic.REQUIRED_KEY.getKeybind().isValid() == false || Configs.Generic.REQUIRED_KEY.getKeybind().isKeybindHeld()))
        {
            if (InfoToggle.FPS.getBooleanValue())
            {
                this.updateFps();
            }

            long currentTime = System.currentTimeMillis();

            // Only update the text once per game tick
            if (currentTime - this.infoUpdateTime >= 50)
            {
                this.updateLines();
                this.infoUpdateTime = currentTime;
            }

            int x = Configs.Generic.TEXT_POS_X.getIntegerValue();
            int y = Configs.Generic.TEXT_POS_Y.getIntegerValue();
            int textColor = Configs.Colors.TEXT_COLOR.getIntegerValue();
            int bgColor = Configs.Colors.TEXT_BACKGROUND_COLOR.getIntegerValue();
            HudAlignment alignment = (HudAlignment) Configs.Generic.HUD_ALIGNMENT.getOptionListValue();
            boolean useBackground = Configs.Generic.USE_TEXT_BACKGROUND.getBooleanValue();
            boolean useShadow = Configs.Generic.USE_FONT_SHADOW.getBooleanValue();

            RenderUtils.renderText(mc, x, y, this.fontScale, textColor, bgColor, alignment, useBackground, useShadow, this.lines);
        }
    }

    @Override
    public void onRenderWorldLast(float partialTicks)
    {
        Minecraft mc = Minecraft.getMinecraft();

        if (Configs.Generic.ENABLED.getBooleanValue() && mc.player != null)
        {
            OverlayRenderer.renderOverlays(mc, partialTicks);
        }
    }

    public void setFontScale(double scale)
    {
        this.fontScale = MathHelper.clamp(scale, 0, 10D);
    }

    public int getSubtitleOffset()
    {
        HudAlignment align = (HudAlignment) Configs.Generic.HUD_ALIGNMENT.getOptionListValue();

        if (align == HudAlignment.BOTTOM_RIGHT)
        {
            Minecraft mc = Minecraft.getMinecraft();
            int offset = (int) (this.lineWrappers.size() * (mc.fontRenderer.FONT_HEIGHT + 2) * this.fontScale);

            return -(offset - 16);
        }

        return 0;
    }

    private void updateFps()
    {
        this.fpsCounter++;

        if (Minecraft.getSystemTime() >= (this.fpsUpdateTime + 1000L))
        {
            this.fpsUpdateTime = Minecraft.getSystemTime();
            this.fps = this.fpsCounter;
            this.fpsCounter = 0;
        }
    }

    public void updateData(Minecraft mc)
    {
        if (mc.world != null)
        {
            if (InfoToggle.SPAWNABLE_SUB_CHUNKS.getBooleanValue() &&
                mc.world.getTotalWorldTime() % Configs.Generic.SPAWNABLE_SUB_CHUNK_CHECK_INTERVAL.getIntegerValue() == 0)
            {
                DataStorage.getInstance().checkQueuedDirtyChunkHightmaps();
            }
        }
    }

    private void updateLines()
    {
        this.lineWrappers.clear();
        this.addedTypes.clear();

        // Get the info line order based on the configs
        List<LinePos> positions = new ArrayList<LinePos>();

        for (InfoToggle toggle : InfoToggle.values())
        {
            if (toggle.getBooleanValue())
            {
                positions.add(new LinePos(toggle.getIntegerValue(), toggle));
            }
        }

        Collections.sort(positions);

        for (LinePos pos : positions)
        {
            this.addLine(pos.type);
        }

        if (Configs.Generic.SORT_LINES_BY_LENGTH.getBooleanValue())
        {
            Collections.sort(this.lineWrappers);

            if (Configs.Generic.SORT_LINES_REVERSED.getBooleanValue())
            {
                Collections.reverse(this.lineWrappers);
            }
        }

        this.lines.clear();

        for (StringHolder holder : this.lineWrappers)
        {
            this.lines.add(holder.str);
        }
    }

    private void addLine(String text)
    {
        this.lineWrappers.add(new StringHolder(text));
    }

    private void addLine(InfoToggle type)
    {
        Minecraft mc = Minecraft.getMinecraft();
        Entity entity = mc.getRenderViewEntity();
        World world = entity.getEntityWorld();
        BlockPos pos = new BlockPos(entity.posX, entity.getEntityBoundingBox().minY, entity.posZ);

        if (type == InfoToggle.FPS)
        {
            this.addLine(String.format("%d fps", this.fps));
        }
        else if (type == InfoToggle.MEMORY_USAGE)
        {
            long memMax = Runtime.getRuntime().maxMemory();
            long memTotal = Runtime.getRuntime().totalMemory();
            long memFree = Runtime.getRuntime().freeMemory();
            long memUsed = memTotal - memFree;

            this.addLine(String.format("Mem: % 2d%% %03d/%03dMB | Allocated: % 2d%% %03dMB",
                    memUsed * 100L / memMax,
                    MiscUtils.bytesToMb(memUsed),
                    MiscUtils.bytesToMb(memMax),
                    memTotal * 100L / memMax,
                    MiscUtils.bytesToMb(memTotal)));
        }
        else if (type == InfoToggle.TIME_REAL)
        {
            try
            {
                SimpleDateFormat sdf = new SimpleDateFormat(Configs.Generic.DATE_FORMAT_REAL.getStringValue());
                this.date.setTime(System.currentTimeMillis());
                this.addLine(sdf.format(this.date));
            }
            catch (Exception e)
            {
                this.addLine("Date formatting failed - Invalid date format string?");
            }
        }
        else if (type == InfoToggle.TIME_WORLD)
        {
            long current = world.getWorldTime();
            long total = world.getTotalWorldTime();
            this.addLine(String.format("World time: %5d - total: %d", current, total));
        }
        else if (type == InfoToggle.TIME_WORLD_FORMATTED)
        {
            try
            {
                long timeDay = (int) world.getWorldTime();
                int day = (int) (timeDay / 24000) + 1;
                // 1 tick = 3.6 seconds in MC (0.2777... seconds IRL)
                int hour = (int) ((timeDay / 1000) + 6) % 24;
                int min = (int) (timeDay / 16.666666) % 60;
                int sec = (int) (timeDay / 0.277777) % 60;

                String str = Configs.Generic.DATE_FORMAT_MINECRAFT.getStringValue();
                str = str.replace("{DAY}",  String.format("%d", day));
                str = str.replace("{HOUR}", String.format("%02d", hour));
                str = str.replace("{MIN}",  String.format("%02d", min));
                str = str.replace("{SEC}",  String.format("%02d", sec));

                this.addLine(str);
            }
            catch (Exception e)
            {
                this.addLine("Date formatting failed - Invalid date format string?");
            }
        }
        else if (type == InfoToggle.SERVER_TPS)
        {
            if (mc.isSingleplayer() && (mc.getIntegratedServer().getTickCounter() % 10) == 0)
            {
                this.data.updateIntegratedServerTPS();
            }

            if (this.data.isServerTPSValid())
            {
                double tps = this.data.getServerTPS();
                double mspt = this.data.getServerMSPT();
                String rst = TextFormatting.RESET.toString();
                String preTps = tps >= 20.0D ? TextFormatting.GREEN.toString() : TextFormatting.RED.toString();
                String preMspt;

                // Carpet server and integrated server have actual meaningful MSPT data available
                if (this.data.isCarpetServer() || mc.isSingleplayer())
                {
                    if      (mspt <= 40) { preMspt = TextFormatting.GREEN.toString(); }
                    else if (mspt <= 45) { preMspt = TextFormatting.YELLOW.toString(); }
                    else if (mspt <= 50) { preMspt = TextFormatting.GOLD.toString(); }
                    else                 { preMspt = TextFormatting.RED.toString(); }

                    this.addLine(String.format("Server TPS: %s%.1f%s MSPT: %s%.1f%s", preTps, tps, rst, preMspt, mspt, rst));
                }
                else
                {
                    if (mspt <= 51) { preMspt = TextFormatting.GREEN.toString(); }
                    else            { preMspt = TextFormatting.RED.toString(); }

                    this.addLine(String.format("Server TPS: %s%.1f%s (MSPT*: %s%.1f%s)", preTps, tps, rst, preMspt, mspt, rst));
                }
            }
            else
            {
                this.addLine("Server TPS: <no valid data>");
            }
        }
        else if (type == InfoToggle.COORDINATES ||
                 type == InfoToggle.DIMENSION)
        {
            // Don't add the same line multiple times
            if (this.addedTypes.contains(InfoToggle.COORDINATES) || this.addedTypes.contains(InfoToggle.DIMENSION))
            {
                return;
            }

            String pre = "";
            StringBuilder str = new StringBuilder(128);

            if (InfoToggle.COORDINATES.getBooleanValue())
            {
                if (Configs.Generic.USE_CUSTOMIZED_COORDINATES.getBooleanValue())
                {
                    try
                    {
                        str.append(String.format(Configs.Generic.COORDINATE_FORMAT_STRING.getStringValue(),
                            entity.posX, entity.getEntityBoundingBox().minY, entity.posZ));
                    }
                    // Uh oh, someone done goofed their format string... :P
                    catch (Exception e)
                    {
                        str.append("broken coordinate format string!");
                    }
                }
                else
                {
                    str.append(String.format("XYZ: %.2f / %.4f / %.2f",
                        entity.posX, entity.getEntityBoundingBox().minY, entity.posZ));
                }

                pre = " / ";
            }

            if (InfoToggle.DIMENSION.getBooleanValue())
            {
                int dimension = world.provider.getDimensionType().getId();
                str.append(String.format(String.format("%sDimensionType ID: %d", pre, dimension)));
            }

            this.addLine(str.toString());

            this.addedTypes.add(InfoToggle.COORDINATES);
            this.addedTypes.add(InfoToggle.DIMENSION);
        }
        else if (type == InfoToggle.BLOCK_POS ||
                 type == InfoToggle.CHUNK_POS ||
                 type == InfoToggle.REGION_FILE)
        {
            // Don't add the same line multiple times
            if (this.addedTypes.contains(InfoToggle.BLOCK_POS) ||
                this.addedTypes.contains(InfoToggle.CHUNK_POS) ||
                this.addedTypes.contains(InfoToggle.REGION_FILE))
            {
                return;
            }

            String pre = "";
            StringBuilder str = new StringBuilder(256);

            if (InfoToggle.BLOCK_POS.getBooleanValue())
            {
                str.append(String.format("Block: %d, %d, %d", pos.getX(), pos.getY(), pos.getZ()));
                pre = " / ";
            }

            if (InfoToggle.CHUNK_POS.getBooleanValue())
            {
                str.append(pre).append(String.format("Sub-Chunk: %d, %d, %d", pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
                pre = " / ";
            }

            if (InfoToggle.REGION_FILE.getBooleanValue())
            {
                str.append(pre).append(String.format("Region: r.%d.%d", pos.getX() >> 9, pos.getZ() >> 9));
            }

            this.addLine(str.toString());

            this.addedTypes.add(InfoToggle.BLOCK_POS);
            this.addedTypes.add(InfoToggle.CHUNK_POS);
            this.addedTypes.add(InfoToggle.REGION_FILE);
        }
        else if (type == InfoToggle.BLOCK_IN_CHUNK)
        {
            this.addLine(String.format("Block: %d, %d, %d within Sub-Chunk: %d, %d, %d",
                        pos.getX() & 0xF, pos.getY() & 0xF, pos.getZ() & 0xF,
                        pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        }
        else if (type == InfoToggle.FACING)
        {
            EnumFacing facing = entity.getHorizontalFacing();
            String str = "Invalid";

            switch (facing)
            {
                case NORTH: str = "Negative Z"; break;
                case SOUTH: str = "Positive Z"; break;
                case WEST:  str = "Negative X"; break;
                case EAST:  str = "Positive X"; break;
                default:
            }

            this.addLine(String.format("Facing: %s (%s)", facing, str));
        }
        else if (type == InfoToggle.LIGHT_LEVEL)
        {
            // Prevent a crash when outside of world
            if (pos.getY() >= 0 && pos.getY() < 256 && mc.world.isBlockLoaded(pos))
            {
                Chunk chunk = mc.world.getChunkFromBlockCoords(pos);

                if (chunk.isEmpty() == false)
                {
                    this.addLine(String.format("Light: %d (block: %d, sky: %d)",
                            chunk.getLightSubtracted(pos, 0),
                            chunk.getLightFor(EnumSkyBlock.BLOCK, pos),
                            chunk.getLightFor(EnumSkyBlock.SKY, pos)));
                }
            }
        }
        else if (type == InfoToggle.ROTATION_YAW ||
                 type == InfoToggle.ROTATION_PITCH ||
                 type == InfoToggle.SPEED)
        {
            // Don't add the same line multiple times
            if (this.addedTypes.contains(InfoToggle.ROTATION_YAW) ||
                this.addedTypes.contains(InfoToggle.ROTATION_PITCH) ||
                this.addedTypes.contains(InfoToggle.SPEED))
            {
                return;
            }

            String pre = "";
            StringBuilder str = new StringBuilder(128);

            if (InfoToggle.ROTATION_YAW.getBooleanValue())
            {
                str.append(String.format("yaw: %.1f", MathHelper.wrapDegrees(entity.rotationYaw)));
                pre = " / ";
            }

            if (InfoToggle.ROTATION_PITCH.getBooleanValue())
            {
                str.append(pre).append(String.format("pitch: %.1f", MathHelper.wrapDegrees(entity.rotationPitch)));
                pre = " / ";
            }

            if (InfoToggle.SPEED.getBooleanValue())
            {
                double dx = entity.posX - entity.lastTickPosX;
                double dy = entity.posY - entity.lastTickPosY;
                double dz = entity.posZ - entity.lastTickPosZ;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                str.append(pre).append(String.format("speed: %.3f m/s", dist * 20));
            }

            this.addLine(str.toString());

            this.addedTypes.add(InfoToggle.ROTATION_YAW);
            this.addedTypes.add(InfoToggle.ROTATION_PITCH);
            this.addedTypes.add(InfoToggle.SPEED);
        }
        else if (type == InfoToggle.CHUNK_SECTIONS)
        {
            this.addLine(String.format("C: %d", ((IMixinRenderGlobal) mc.renderGlobal).getRenderedChunksInvoker()));
        }
        else if (type == InfoToggle.CHUNK_SECTIONS_FULL)
        {
            this.addLine(mc.renderGlobal.getDebugInfoRenders());
        }
        else if (type == InfoToggle.CHUNK_UPDATES)
        {
            this.addLine(String.format("Chunk updates: %d", RenderChunk.renderChunksUpdated));
        }
        else if (type == InfoToggle.CHUNK_UNLOAD_ORDER)
        {
            int bucket = MiscUtils.getChunkUnloadBucket(pos.getX() >> 4, pos.getZ() >> 4);
            this.addLine(String.format("Chunk unload bucket: %d", bucket));
        }
        else if (type == InfoToggle.MP_CHUNK_CACHE)
        {
            this.addLine(mc.world.getProviderName());
        }
        else if (type == InfoToggle.PARTICLE_COUNT)
        {
            this.addLine(String.format("P: %s", mc.effectRenderer.getStatistics()));
        }
        else if (type == InfoToggle.DIFFICULTY)
        {
            if (mc.world.isBlockLoaded(pos))
            {
                DifficultyInstance diff = mc.world.getDifficultyForLocation(pos);

                if (mc.isIntegratedServerRunning() && mc.getIntegratedServer() != null)
                {
                    EntityPlayerMP player = mc.getIntegratedServer().getPlayerList().getPlayerByUUID(mc.player.getUniqueID());

                    if (player != null)
                    {
                        diff = player.world.getDifficultyForLocation(new BlockPos(player));
                    }
                }

                this.addLine(String.format("Local Difficulty: %.2f // %.2f (Day %d)",
                        diff.getAdditionalDifficulty(), diff.getClampedAdditionalDifficulty(), mc.world.getWorldTime() / 24000L));
            }
        }
        else if (type == InfoToggle.BIOME)
        {
            // Prevent a crash when outside of world
            if (pos.getY() >= 0 && pos.getY() < 256 && mc.world.isBlockLoaded(pos))
            {
                Chunk chunk = mc.world.getChunkFromBlockCoords(pos);

                if (chunk.isEmpty() == false)
                {
                    this.addLine("Biome: " + chunk.getBiome(pos, mc.world.getBiomeProvider()).getBiomeName());
                }
            }
        }
        else if (type == InfoToggle.BIOME_REG_NAME)
        {
            // Prevent a crash when outside of world
            if (pos.getY() >= 0 && pos.getY() < 256 && mc.world.isBlockLoaded(pos))
            {
                Chunk chunk = mc.world.getChunkFromBlockCoords(pos);

                if (chunk.isEmpty() == false)
                {
                    Biome biome = chunk.getBiome(pos, mc.world.getBiomeProvider());
                    ResourceLocation rl = Biome.REGISTRY.getNameForObject(biome);
                    String name = rl != null ? rl.toString() : "?";
                    this.addLine("Biome reg name: " + name);
                }
            }
        }
        else if (type == InfoToggle.ENTITIES)
        {
            String ent = mc.renderGlobal.getDebugInfoEntities();

            int p = ent.indexOf(",");

            if (p != -1)
            {
                ent = ent.substring(0, p);
            }

            this.addLine(ent);
        }
        else if (type == InfoToggle.SLIME_CHUNK)
        {
            if (world.provider.isSurfaceWorld() == false)
            {
                return;
            }

            String result;
            int dimension = entity.dimension;

            if (this.data.isWorldSeedKnown(dimension))
            {
                long seed = this.data.getWorldSeed(dimension);

                if (MiscUtils.canSlimeSpawnAt(pos.getX(), pos.getZ(), seed))
                {
                    result = TextFormatting.GREEN.toString() + "YES" + TextFormatting.RESET.toString() + TextFormatting.WHITE.toString();
                }
                else
                {
                    result = TextFormatting.RED.toString() + "NO" + TextFormatting.RESET.toString() + TextFormatting.WHITE.toString();
                }
            }
            else
            {
                result = "<world seed not known>";
            }

            this.addLine("Slime chunk: " + result);
        }
        else if (type == InfoToggle.LOOKING_AT_ENTITY)
        {
            if (mc.objectMouseOver != null &&
                mc.objectMouseOver.typeOfHit == RayTraceResult.Type.ENTITY &&
                mc.objectMouseOver.entityHit != null)
            {
                Entity target = mc.objectMouseOver.entityHit;

                if (target instanceof EntityLivingBase)
                {
                    EntityLivingBase living = (EntityLivingBase) target;
                    this.addLine(String.format("Entity: %s - HP: %.1f / %.1f",
                            target.getName(), living.getHealth(), living.getMaxHealth()));
                }
                else
                {
                    this.addLine(String.format("Entity: %s", target.getName()));
                }
            }
        }
        else if (type == InfoToggle.ENTITY_REG_NAME)
        {
            if (mc.objectMouseOver != null &&
                mc.objectMouseOver.typeOfHit == RayTraceResult.Type.ENTITY &&
                mc.objectMouseOver.entityHit != null)
            {
                ResourceLocation regName = EntityList.getKey(mc.objectMouseOver.entityHit);

                if (regName != null)
                {
                    this.addLine(String.format("Entity reg name: %s", regName.toString()));
                }
            }
        }
        else if (type == InfoToggle.LOOKING_AT_BLOCK ||
                 type == InfoToggle.LOOKING_AT_BLOCK_CHUNK)
        {
            // Don't add the same line multiple times
            if (this.addedTypes.contains(InfoToggle.LOOKING_AT_BLOCK) ||
                this.addedTypes.contains(InfoToggle.LOOKING_AT_BLOCK_CHUNK))
            {
                return;
            }

            if (mc.objectMouseOver != null &&
                mc.objectMouseOver.typeOfHit == RayTraceResult.Type.BLOCK &&
                mc.objectMouseOver.getBlockPos() != null)
            {
                BlockPos lookPos = mc.objectMouseOver.getBlockPos();
                String pre = "";
                StringBuilder str = new StringBuilder(128);

                if (InfoToggle.LOOKING_AT_BLOCK.getBooleanValue())
                {
                    str.append(String.format("Looking at block: %d, %d, %d", lookPos.getX(), lookPos.getY(), lookPos.getZ()));
                    pre = " // ";
                }

                if (InfoToggle.LOOKING_AT_BLOCK_CHUNK.getBooleanValue())
                {
                    str.append(pre).append(String.format("Block: %d, %d, %d in Sub-Chunk: %d, %d, %d",
                            lookPos.getX() & 0xF, lookPos.getY() & 0xF, lookPos.getZ() & 0xF,
                            lookPos.getX() >> 4, lookPos.getY() >> 4, lookPos.getZ() >> 4));
                }

                this.addLine(str.toString());

                this.addedTypes.add(InfoToggle.LOOKING_AT_BLOCK);
                this.addedTypes.add(InfoToggle.LOOKING_AT_BLOCK_CHUNK);
            }
        }
        else if (type == InfoToggle.BLOCK_PROPS)
        {
            this.getBlockProperties(mc);
        }
        else if (type == InfoToggle.SPAWNABLE_SUB_CHUNKS)
        {
            int value = DataStorage.getInstance().getSpawnableSubChunkCountFor(pos.getX() >> 4, pos.getZ() >> 4);

            if (value >= 0)
            {
                this.addLine(String.format("Spawnable sub-chunks: %d (y: 0 - %d)", value, value * 16 - 1));
            }
            else
            {
                this.addLine(String.format("Spawnable sub-chunks: <no data>"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Comparable<T>> void getBlockProperties(Minecraft mc)
    {
        if (mc.objectMouseOver != null &&
            mc.objectMouseOver.typeOfHit == RayTraceResult.Type.BLOCK &&
            mc.objectMouseOver.getBlockPos() != null)
        {
            BlockPos posLooking = mc.objectMouseOver.getBlockPos();
            IBlockState state = mc.world.getBlockState(posLooking);

            if (mc.world.getWorldType() != WorldType.DEBUG_ALL_BLOCK_STATES)
            {
                state = state.getActualState(mc.world, posLooking);
            }

            this.addLine(String.valueOf(Block.REGISTRY.getNameForObject(state.getBlock())));

            for (Entry <IProperty<?>, Comparable<?>> entry : state.getProperties().entrySet())
            {
                IProperty<T> property = (IProperty<T>) entry.getKey();
                T value = (T) entry.getValue();
                String valueName = property.getName(value);

                if (property instanceof PropertyDirection)
                {
                    valueName = TextFormatting.GOLD + valueName;
                }
                else if (Boolean.TRUE.equals(value))
                {
                    valueName = TextFormatting.GREEN + valueName;
                }
                else if (Boolean.FALSE.equals(value))
                {
                    valueName = TextFormatting.RED + valueName;
                }
                else if (Integer.class.equals(property.getValueClass()))
                {
                    valueName = TextFormatting.GREEN + valueName;
                }

                this.addLine(property.getName() + ": " + valueName);
            }
        }
    }

    private class StringHolder implements Comparable<StringHolder>
    {
        public final String str;

        public StringHolder(String str)
        {
            this.str = str;
        }

        @Override
        public int compareTo(StringHolder other)
        {
            int lenThis = this.str.length();
            int lenOther = other.str.length();

            if (lenThis == lenOther)
            {
                return 0;
            }

            return this.str.length() > other.str.length() ? -1 : 1;
        }
    }

    private static class LinePos implements Comparable<LinePos>
    {
        private final int position;
        private final InfoToggle type;

        private LinePos(int position, InfoToggle type)
        {
            this.position = position;
            this.type = type;
        }

        @Override
        public int compareTo(LinePos other)
        {
            if (this.position < 0)
            {
                return other.position >= 0 ? 1 : 0;
            }
            else if (other.position < 0 && this.position >= 0)
            {
                return -1;
            }

            return this.position < other.position ? -1 : (this.position > other.position ? 1 : 0);
        }
    }
}