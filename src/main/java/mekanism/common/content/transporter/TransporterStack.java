package mekanism.common.content.transporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mekanism.api.NBTConstants;
import mekanism.api.math.MathUtils;
import mekanism.api.text.EnumColor;
import mekanism.common.content.network.transmitter.LogisticalTransporterBase;
import mekanism.common.content.transporter.TransporterPathfinder.Destination;
import mekanism.common.content.transporter.TransporterPathfinder.IdlePathData;
import mekanism.common.lib.inventory.IAdvancedTransportEjector;
import mekanism.common.lib.inventory.TransitRequest;
import mekanism.common.lib.inventory.TransitRequest.TransitResponse;
import mekanism.common.util.NBTUtils;
import mekanism.common.util.TransporterUtils;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TransporterStack {

    public ItemStack itemStack = ItemStack.EMPTY;

    public int progress;

    public EnumColor color = null;

    public boolean initiatedPath = false;

    public Direction idleDir = null;
    public BlockPos originalLocation;
    public BlockPos homeLocation;
    private BlockPos clientNext;
    private BlockPos clientPrev;
    private Path pathType;
    private List<BlockPos> pathToTarget = new ArrayList<>();

    public static TransporterStack readFromNBT(CompoundTag nbtTags) {
        TransporterStack stack = new TransporterStack();
        stack.read(nbtTags);
        return stack;
    }

    public static TransporterStack readFromUpdate(CompoundTag nbtTags) {
        TransporterStack stack = new TransporterStack();
        stack.readFromUpdateTag(nbtTags);
        return stack;
    }

    public static TransporterStack readFromPacket(FriendlyByteBuf dataStream) {
        TransporterStack stack = new TransporterStack();
        stack.read(dataStream);
        if (stack.progress == 0) {
            stack.progress = 5;
        }
        return stack;
    }

    public void write(FriendlyByteBuf buf, BlockPos pos) {
        buf.writeVarInt(TransporterUtils.getColorIndex(color));
        buf.writeVarInt(progress);
        buf.writeBlockPos(originalLocation);
        buf.writeEnum(pathType);
        buf.writeNullable(getNext(pos), FriendlyByteBuf::writeBlockPos);
        buf.writeBlockPos(getPrev(pos));
        buf.writeItem(itemStack);
    }

    public void read(FriendlyByteBuf dataStream) {
        color = TransporterUtils.readColor(dataStream.readVarInt());
        progress = dataStream.readVarInt();
        originalLocation = dataStream.readBlockPos();
        pathType = dataStream.readEnum(Path.class);
        //TODO - 1.20.4: SP: The clientNext and clientPrev have issues in single player as they won't get set
        // though in all our use cases we are forcing a read/write to prevent mutation or leaking from one side to another
        // so at least for now it doesn't fully matter
        clientNext = dataStream.readNullable(FriendlyByteBuf::readBlockPos);
        clientPrev = dataStream.readBlockPos();
        itemStack = dataStream.readItem();
    }

    public void writeToUpdateTag(LogisticalTransporterBase transporter, CompoundTag updateTag) {
        if (color != null) {
            NBTUtils.writeEnum(updateTag, NBTConstants.COLOR, color);
        }
        updateTag.putInt(NBTConstants.PROGRESS, progress);
        updateTag.put(NBTConstants.ORIGINAL_LOCATION, NbtUtils.writeBlockPos(originalLocation));
        NBTUtils.writeEnum(updateTag, NBTConstants.PATH_TYPE, pathType);
        BlockPos next = getNext(transporter);
        if (next != null) {
            updateTag.put(NBTConstants.CLIENT_NEXT, NbtUtils.writeBlockPos(next));
        }
        updateTag.put(NBTConstants.CLIENT_PREVIOUS, NbtUtils.writeBlockPos(getPrev(transporter)));
        itemStack.save(updateTag);
    }

    public void readFromUpdateTag(CompoundTag updateTag) {
        this.color = NBTUtils.getEnum(updateTag, NBTConstants.COLOR, TransporterUtils::readColor);
        progress = updateTag.getInt(NBTConstants.PROGRESS);
        NBTUtils.setBlockPosIfPresent(updateTag, NBTConstants.ORIGINAL_LOCATION, coord -> originalLocation = coord);
        NBTUtils.setEnumIfPresent(updateTag, NBTConstants.PATH_TYPE, Path::byIndexStatic, type -> pathType = type);
        NBTUtils.setBlockPosIfPresent(updateTag, NBTConstants.CLIENT_NEXT, coord -> clientNext = coord);
        NBTUtils.setBlockPosIfPresent(updateTag, NBTConstants.CLIENT_PREVIOUS, coord -> clientPrev = coord);
        itemStack = ItemStack.of(updateTag);
    }

    public void write(CompoundTag nbtTags) {
        if (color != null) {
            NBTUtils.writeEnum(nbtTags, NBTConstants.COLOR, color);
        }

        nbtTags.putInt(NBTConstants.PROGRESS, progress);
        nbtTags.put(NBTConstants.ORIGINAL_LOCATION, NbtUtils.writeBlockPos(originalLocation));

        if (idleDir != null) {
            NBTUtils.writeEnum(nbtTags, NBTConstants.IDLE_DIR, idleDir);
        }
        if (homeLocation != null) {
            nbtTags.put(NBTConstants.HOME_LOCATION, NbtUtils.writeBlockPos(homeLocation));
        }
        NBTUtils.writeEnum(nbtTags, NBTConstants.PATH_TYPE, pathType);
        itemStack.save(nbtTags);
    }

    public void read(CompoundTag nbtTags) {
        this.color = NBTUtils.getEnum(nbtTags, NBTConstants.COLOR, TransporterUtils::readColor);
        progress = nbtTags.getInt(NBTConstants.PROGRESS);
        NBTUtils.setBlockPosIfPresent(nbtTags, NBTConstants.ORIGINAL_LOCATION, coord -> originalLocation = coord);
        NBTUtils.setEnumIfPresent(nbtTags, NBTConstants.IDLE_DIR, Direction::from3DDataValue, dir -> idleDir = dir);
        NBTUtils.setBlockPosIfPresent(nbtTags, NBTConstants.HOME_LOCATION, coord -> homeLocation = coord);
        NBTUtils.setEnumIfPresent(nbtTags, NBTConstants.PATH_TYPE, Path::byIndexStatic, type -> pathType = type);
        itemStack = ItemStack.of(nbtTags);
    }

    private void setPath(Level world, List<BlockPos> path, Path type, boolean updateFlowing) {
        //Make sure old path isn't null
        if (updateFlowing && pathType != Path.NONE) {
            //Only update the actual flowing stacks if we want to modify more than our current stack
            TransporterManager.remove(world, this);
        }
        pathToTarget = path;
        pathType = type;
        if (updateFlowing && pathType != Path.NONE) {
            //Only update the actual flowing stacks if we want to modify more than our current stack
            TransporterManager.add(world, this);
        }
    }

    public boolean hasPath() {
        return pathToTarget != null && pathToTarget.size() >= 2;
    }

    public List<BlockPos> getPath() {
        return pathToTarget;
    }

    public Path getPathType() {
        return pathType;
    }

    public TransitResponse recalculatePath(TransitRequest request, LogisticalTransporterBase transporter, int min) {
        return recalculatePath(request, transporter, min, true);
    }

    public final TransitResponse recalculatePath(TransitRequest request, BlockEntity ignored, LogisticalTransporterBase transporter, int min, boolean updateFlowing) {
        return recalculatePath(request, transporter, min, updateFlowing);
    }

    public TransitResponse recalculatePath(TransitRequest request, LogisticalTransporterBase transporter, int min, boolean updateFlowing) {
        return recalculatePath(request, transporter, min, updateFlowing, Collections.emptyMap());
    }

    public TransitResponse recalculatePath(TransitRequest request, LogisticalTransporterBase transporter, int min,
          Map<GlobalPos, Set<TransporterStack>> additionalFlowingStacks) {
        return recalculatePath(request, transporter, min, false, additionalFlowingStacks);
    }

    private TransitResponse recalculatePath(TransitRequest request, LogisticalTransporterBase transporter, int min, boolean updateFlowing,
          Map<GlobalPos, Set<TransporterStack>> additionalFlowingStacks) {
        Destination newPath = TransporterPathfinder.getNewBasePath(transporter, this, request, min, additionalFlowingStacks);
        if (newPath == null) {
            return request.getEmptyResponse();
        }
        idleDir = null;
        setPath(transporter.getLevel(), newPath.getPath(), Path.DEST, updateFlowing);
        initiatedPath = true;
        return newPath.getResponse();
    }

    public <BE extends BlockEntity & IAdvancedTransportEjector> TransitResponse recalculateRRPath(TransitRequest request, BE outputter, LogisticalTransporterBase transporter, int min) {
        return recalculateRRPath(request, outputter, transporter, min, true);
    }

    public <BE extends BlockEntity & IAdvancedTransportEjector> TransitResponse recalculateRRPath(TransitRequest request, BE outputter, LogisticalTransporterBase transporter, int min, boolean updateFlowing) {
        Destination newPath = TransporterPathfinder.getNewRRPath(transporter, this, request, outputter, min);
        if (newPath == null) {
            return request.getEmptyResponse();
        }
        idleDir = null;
        setPath(transporter.getLevel(), newPath.getPath(), Path.DEST, updateFlowing);
        initiatedPath = true;
        return newPath.getResponse();
    }

    public boolean calculateIdle(LogisticalTransporterBase transporter) {
        IdlePathData newPath = TransporterPathfinder.getIdlePath(transporter, this);
        if (newPath == null) {
            return false;
        }
        if (newPath.type() == Path.HOME) {
            idleDir = null;
        }
        setPath(transporter.getLevel(), newPath.path(), newPath.type(), true);
        originalLocation = transporter.getBlockPos();
        initiatedPath = true;
        return true;
    }

    public boolean isFinal(LogisticalTransporterBase transporter) {
        return pathToTarget.indexOf(transporter.getBlockPos()) == (pathType == Path.NONE ? 0 : 1);
    }

    public BlockPos getNext(LogisticalTransporterBase transporter) {
        return transporter.isRemote() ? clientNext : getNext(transporter.getBlockPos());
    }

    private BlockPos getNext(BlockPos pos) {
        int index = pathToTarget.indexOf(pos) - 1;
        if (index < 0) {
            return null;
        }
        return pathToTarget.get(index);
    }

    public BlockPos getPrev(LogisticalTransporterBase transporter) {
        return transporter.isRemote() ? clientPrev : getPrev(transporter.getBlockPos());
    }

    private BlockPos getPrev(BlockPos pos) {
        int index = pathToTarget.indexOf(pos) + 1;
        if (index < pathToTarget.size()) {
            return pathToTarget.get(index);
        }
        return originalLocation;
    }

    public Direction getSide(LogisticalTransporterBase transporter) {
        Direction side = null;
        if (progress < 50) {
            BlockPos prev = getPrev(transporter);
            if (prev != null) {
                side = WorldUtils.sideDifference(transporter.getBlockPos(), prev);
            }
        } else {
            BlockPos next = getNext(transporter);
            if (next != null) {
                side = WorldUtils.sideDifference(next, transporter.getBlockPos());
            }
        }
        //sideDifference can return null
        //TODO: Look into implications further about what side should be returned.
        // This is mainly to stop a crash I randomly encountered but was unable to reproduce.
        // (I believe the difference returns null when it is the "same" transporter somehow or something)
        return side == null ? Direction.DOWN : side;
    }

    @Contract("null, _, _ -> false")
    public boolean canInsertToTransporter(@Nullable LogisticalTransporterBase transmitter, Direction from, @Nullable LogisticalTransporterBase transporterFrom) {
        return transmitter != null && canInsertToTransporterNN(transmitter, from, transporterFrom);
    }

    public boolean canInsertToTransporterNN(@NotNull LogisticalTransporterBase transporter, Direction from, @Nullable BlockEntity tileFrom) {
        //If the color is valid, make sure that the connection is valid
        EnumColor color = transporter.getColor();
        return (color == null || color == this.color) && transporter.canConnectMutual(from.getOpposite(), tileFrom);
    }

    public boolean canInsertToTransporterNN(@NotNull LogisticalTransporterBase transporter, Direction from, @Nullable LogisticalTransporterBase transporterFrom) {
        //If the color is valid, make sure that the connection is valid
        EnumColor color = transporter.getColor();
        return (color == null || color == this.color) && transporter.canConnectMutual(from.getOpposite(), transporterFrom);
    }

    public BlockPos getDest() {
        return pathToTarget.get(0);
    }

    @Nullable
    public Direction getSideOfDest() {
        if (hasPath()) {
            BlockPos lastTransporter = pathToTarget.get(1);
            return WorldUtils.sideDifference(lastTransporter, getDest());
        }
        return null;
    }

    public enum Path {
        DEST,
        HOME,
        NONE;

        private static final Path[] PATHS = values();

        public static Path byIndexStatic(int index) {
            return MathUtils.getByIndexMod(PATHS, index);
        }
    }
}