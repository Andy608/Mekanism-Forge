package mekanism.common.content.network.transmitter;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.NBTConstants;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.ChemicalTankBuilder;
import mekanism.api.chemical.ChemicalType;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.chemical.IChemicalTank;
import mekanism.api.chemical.gas.IGasTank;
import mekanism.api.chemical.infuse.IInfusionTank;
import mekanism.api.chemical.merged.BoxedChemical;
import mekanism.api.chemical.merged.BoxedChemicalStack;
import mekanism.api.chemical.merged.MergedChemicalTank;
import mekanism.api.chemical.merged.MergedChemicalTank.Current;
import mekanism.api.chemical.pigment.IPigmentTank;
import mekanism.api.chemical.slurry.ISlurryTank;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.capabilities.chemical.BoxedChemicalHandler;
import mekanism.common.capabilities.chemical.dynamic.IGasTracker;
import mekanism.common.capabilities.chemical.dynamic.IInfusionTracker;
import mekanism.common.capabilities.chemical.dynamic.IPigmentTracker;
import mekanism.common.capabilities.chemical.dynamic.ISlurryTracker;
import mekanism.common.content.network.BoxedChemicalNetwork;
import mekanism.common.lib.transmitter.CompatibleTransmitterValidator;
import mekanism.common.lib.transmitter.CompatibleTransmitterValidator.CompatibleChemicalTransmitterValidator;
import mekanism.common.lib.transmitter.ConnectionType;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.lib.transmitter.acceptor.BoxedChemicalAcceptorCache;
import mekanism.common.tier.TubeTier;
import mekanism.common.tile.transmitter.TileEntityTransmitter;
import mekanism.common.upgrade.transmitter.PressurizedTubeUpgradeData;
import mekanism.common.upgrade.transmitter.TransmitterUpgradeData;
import mekanism.common.util.ChemicalUtil;
import mekanism.common.util.EnumUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.NBTUtils;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BoxedPressurizedTube extends BufferedTransmitter<BoxedChemicalHandler, BoxedChemicalNetwork, BoxedChemicalStack, BoxedPressurizedTube>
      implements IGasTracker, IInfusionTracker, IPigmentTracker, ISlurryTracker, IUpgradeableTransmitter<PressurizedTubeUpgradeData> {

    public final TubeTier tier;
    public final MergedChemicalTank chemicalTank;
    private final List<IGasTank> gasTanks;
    private final List<IInfusionTank> infusionTanks;
    private final List<IPigmentTank> pigmentTanks;
    private final List<ISlurryTank> slurryTanks;
    @NotNull
    public BoxedChemicalStack saveShare = BoxedChemicalStack.EMPTY;

    public BoxedPressurizedTube(IBlockProvider blockProvider, TileEntityTransmitter tile) {
        super(tile, TransmissionType.GAS, TransmissionType.INFUSION, TransmissionType.PIGMENT, TransmissionType.SLURRY);
        this.tier = Attribute.getTier(blockProvider, TubeTier.class);
        chemicalTank = MergedChemicalTank.create(
              ChemicalTankBuilder.GAS.createAllValid(getCapacity(), this),
              ChemicalTankBuilder.INFUSION.createAllValid(getCapacity(), this),
              ChemicalTankBuilder.PIGMENT.createAllValid(getCapacity(), this),
              ChemicalTankBuilder.SLURRY.createAllValid(getCapacity(), this)
        );
        gasTanks = Collections.singletonList(chemicalTank.getGasTank());
        infusionTanks = Collections.singletonList(chemicalTank.getInfusionTank());
        pigmentTanks = Collections.singletonList(chemicalTank.getPigmentTank());
        slurryTanks = Collections.singletonList(chemicalTank.getSlurryTank());
    }

    @Override
    protected BoxedChemicalAcceptorCache createAcceptorCache() {
        return new BoxedChemicalAcceptorCache(getTransmitterTile());
    }

    @Override
    public BoxedChemicalAcceptorCache getAcceptorCache() {
        return (BoxedChemicalAcceptorCache) super.getAcceptorCache();
    }

    @Override
    public TubeTier getTier() {
        return tier;
    }

    @Override
    public void pullFromAcceptors() {
        Set<Direction> connections = getConnections(ConnectionType.PULL);
        if (!connections.isEmpty()) {
            for (BoxedChemicalHandler connectedAcceptor : getAcceptorCache().getConnectedAcceptors(connections)) {
                //Note: We recheck the buffer each time in case we ended up accepting chemical somewhere
                // and our buffer changed and is no longer empty
                BoxedChemicalStack bufferWithFallback = getBufferWithFallback();
                if (bufferWithFallback.isEmpty()) {
                    //If the buffer is empty we need to try against each chemical type
                    for (ChemicalType chemicalType : EnumUtils.CHEMICAL_TYPES) {
                        if (pullFromAcceptor(connectedAcceptor, chemicalType, bufferWithFallback, true)) {
                            //If we successfully pulled into this tube, don't bother checking the other chemical types
                            break;
                        }
                    }
                } else {
                    pullFromAcceptor(connectedAcceptor, bufferWithFallback.getChemicalType(), bufferWithFallback, false);
                }
            }
        }
    }

    private boolean pullFromAcceptor(BoxedChemicalHandler acceptor, ChemicalType chemicalType, BoxedChemicalStack bufferWithFallback, boolean bufferIsEmpty) {
        IChemicalHandler<?, ?> handler = acceptor.getHandlerFor(chemicalType);
        if (handler != null) {
            return pullFromAcceptor(handler, bufferWithFallback, chemicalType, bufferIsEmpty);
        }
        return false;
    }

    /**
     * @param connectedAcceptor  The acceptor
     * @param bufferWithFallback The buffer of the network
     * @param chemicalType       The chemical type of the buffer
     * @param bufferIsEmpty      {@code true} if the buffer is empty, {@code false} otherwise
     *
     * @return {@code true} if we successfully pulled a chemical, {@code false} if we were unable to pull a chemical.
     */
    private <CHEMICAL extends Chemical<CHEMICAL>, STACK extends ChemicalStack<CHEMICAL>, HANDLER extends IChemicalHandler<CHEMICAL, STACK>>
    boolean pullFromAcceptor(HANDLER connectedAcceptor, BoxedChemicalStack bufferWithFallback, ChemicalType chemicalType, boolean bufferIsEmpty) {
        long availablePull = getAvailablePull(chemicalType);
        STACK received;
        if (bufferIsEmpty) {
            //If we don't have a chemical stored try pulling as much as we are able to
            received = connectedAcceptor.extractChemical(availablePull, Action.SIMULATE);
        } else {
            //Otherwise, try draining the same type of chemical we have stored requesting up to as much as we are able to pull
            // We do this to better support multiple tanks in case the chemical we have stored we could pull out of a block's
            // second tank but just asking to drain a specific amount
            received = connectedAcceptor.extractChemical(ChemicalUtil.copyWithAmount((STACK) bufferWithFallback.getChemicalStack(), availablePull), Action.SIMULATE);
        }
        if (!received.isEmpty() && takeChemical(chemicalType, received, Action.SIMULATE).isEmpty()) {
            //If we received some chemical and are able to insert it all, then actually extract it and insert it into our thing.
            // Note: We extract first after simulating ourselves because if the target gave a faulty simulation value, we want to handle it properly
            // and not accidentally dupe anything, and we know our simulation we just performed on taking it is valid
            takeChemical(chemicalType, connectedAcceptor.extractChemical(received, Action.EXECUTE), Action.EXECUTE);
            return true;
        }
        return false;
    }

    private long getAvailablePull(ChemicalType chemicalType) {
        if (hasTransmitterNetwork()) {
            return Math.min(tier.getTubePullAmount(), getTransmitterNetwork().chemicalTank.getTankForType(chemicalType).getNeeded());
        }
        return Math.min(tier.getTubePullAmount(), chemicalTank.getTankForType(chemicalType).getNeeded());
    }

    @Nullable
    @Override
    public PressurizedTubeUpgradeData getUpgradeData() {
        return new PressurizedTubeUpgradeData(redstoneReactive, getConnectionTypesRaw(), getShare());
    }

    @Override
    public boolean dataTypeMatches(@NotNull TransmitterUpgradeData data) {
        return data instanceof PressurizedTubeUpgradeData;
    }

    @Override
    public void parseUpgradeData(@NotNull PressurizedTubeUpgradeData data) {
        redstoneReactive = data.redstoneReactive;
        setConnectionTypesRaw(data.connectionTypes);
        takeChemical(data.contents, Action.EXECUTE);
    }

    @Override
    public void read(HolderLookup.Provider provider, @NotNull CompoundTag nbtTags) {
        super.read(provider, nbtTags);
        if (nbtTags.contains(NBTConstants.BOXED_CHEMICAL, Tag.TAG_COMPOUND)) {
            saveShare = BoxedChemicalStack.parseOptional(provider, nbtTags.getCompound(NBTConstants.BOXED_CHEMICAL));
        } else {
            saveShare = BoxedChemicalStack.EMPTY;
        }
        setStackClearOthers(saveShare.getChemicalStack(), chemicalTank.getTankForType(saveShare.getChemicalType()));
    }

    @SuppressWarnings("unchecked")
    private <CHEMICAL extends Chemical<CHEMICAL>, STACK extends ChemicalStack<CHEMICAL>> void setStackClearOthers(STACK stack, IChemicalTank<?, ?> tank) {
        ((IChemicalTank<CHEMICAL, STACK>) tank).setStack(stack);
        for (IChemicalTank<?, ?> tankToClear : chemicalTank.getAllTanks()) {
            if (tank != tankToClear) {
                tankToClear.setEmpty();
            }
        }
    }

    @NotNull
    @Override
    public CompoundTag write(HolderLookup.Provider provider, @NotNull CompoundTag nbtTags) {
        super.write(provider, nbtTags);
        if (hasTransmitterNetwork()) {
            getTransmitterNetwork().validateSaveShares(this);
        }
        if (saveShare.isEmpty()) {
            nbtTags.remove(NBTConstants.BOXED_CHEMICAL);
        } else {
            nbtTags.put(NBTConstants.BOXED_CHEMICAL, saveShare.save(provider));
        }
        return nbtTags;
    }

    @Override
    public BoxedChemicalNetwork createEmptyNetworkWithID(UUID networkID) {
        return new BoxedChemicalNetwork(networkID);
    }

    @Override
    public BoxedChemicalNetwork createNetworkByMerging(Collection<BoxedChemicalNetwork> toMerge) {
        return new BoxedChemicalNetwork(toMerge);
    }

    @Override
    public CompatibleTransmitterValidator<BoxedChemicalHandler, BoxedChemicalNetwork, BoxedPressurizedTube> getNewOrphanValidator() {
        return new CompatibleChemicalTransmitterValidator(this);
    }

    @Override
    public boolean isValidTransmitter(TileEntityTransmitter transmitter, Direction side) {
        if (super.isValidTransmitter(transmitter, side) && transmitter.getTransmitter() instanceof BoxedPressurizedTube other) {
            BoxedChemical buffer = getBufferWithFallback().getType();
            if (buffer.isEmpty() && hasTransmitterNetwork() && getTransmitterNetwork().getPrevTransferAmount() > 0) {
                buffer = getTransmitterNetwork().lastChemical;
            }
            BoxedChemical otherBuffer = other.getBufferWithFallback().getType();
            if (otherBuffer.isEmpty() && other.hasTransmitterNetwork() && other.getTransmitterNetwork().getPrevTransferAmount() > 0) {
                otherBuffer = other.getTransmitterNetwork().lastChemical;
            }
            return buffer.isEmpty() || otherBuffer.isEmpty() || buffer.equals(otherBuffer);
        }
        return false;
    }

    @Override
    protected boolean canHaveIncompatibleNetworks() {
        return true;
    }

    @Override
    public long getCapacity() {
        return tier.getTubeCapacity();
    }

    @NotNull
    @Override
    public BoxedChemicalStack releaseShare() {
        BoxedChemicalStack ret;
        Current current = chemicalTank.getCurrent();
        if (current == Current.EMPTY) {
            ret = BoxedChemicalStack.EMPTY;
        } else {
            IChemicalTank<?, ?> tank = chemicalTank.getTankFromCurrent(current);
            ret = BoxedChemicalStack.box(tank.getStack());
            tank.setEmpty();
        }
        return ret;
    }

    @NotNull
    @Override
    public BoxedChemicalStack getShare() {
        Current current = chemicalTank.getCurrent();
        if (current == Current.EMPTY) {
            return BoxedChemicalStack.EMPTY;
        }
        return BoxedChemicalStack.box(chemicalTank.getTankFromCurrent(current).getStack());
    }

    @Override
    public boolean noBufferOrFallback() {
        return getBufferWithFallback().isEmpty();
    }

    @NotNull
    @Override
    public BoxedChemicalStack getBufferWithFallback() {
        BoxedChemicalStack buffer = getShare();
        //If we don't have a buffer try falling back to the network's buffer
        if (buffer.isEmpty() && hasTransmitterNetwork()) {
            return getTransmitterNetwork().getBuffer();
        }
        return buffer;
    }

    @Override
    public void takeShare() {
        if (hasTransmitterNetwork()) {
            BoxedChemicalNetwork transmitterNetwork = getTransmitterNetwork();
            Current networkCurrent = transmitterNetwork.chemicalTank.getCurrent();
            if (networkCurrent != Current.EMPTY && !saveShare.isEmpty()) {
                ChemicalStack<?> chemicalStack = saveShare.getChemicalStack();
                long amount = chemicalStack.getAmount();
                MekanismUtils.logMismatchedStackSize(transmitterNetwork.chemicalTank.getTankFromCurrent(networkCurrent).shrinkStack(amount, Action.EXECUTE), amount);
                setStackClearOthers(chemicalStack, chemicalTank.getTankFromCurrent(networkCurrent));
            }
        }
    }

    public void takeChemical(BoxedChemicalStack stack, Action action) {
        takeChemical(stack.getChemicalType(), stack.getChemicalStack(), action);
    }

    /**
     * @return remainder
     */
    @NotNull
    @SuppressWarnings("unchecked")
    private <CHEMICAL extends Chemical<CHEMICAL>, STACK extends ChemicalStack<CHEMICAL>> STACK takeChemical(ChemicalType type, STACK stack, Action action) {
        IChemicalTank<CHEMICAL, STACK> tank;
        if (hasTransmitterNetwork()) {
            tank = (IChemicalTank<CHEMICAL, STACK>) getTransmitterNetwork().chemicalTank.getTankForType(type);
        } else {
            tank = (IChemicalTank<CHEMICAL, STACK>) chemicalTank.getTankForType(type);
        }
        return tank.insert(stack, action, AutomationType.INTERNAL);
    }

    @NotNull
    @Override
    public List<IGasTank> getGasTanks(@Nullable Direction side) {
        if (hasTransmitterNetwork()) {
            return getTransmitterNetwork().getGasTanks(side);
        }
        return gasTanks;
    }

    @NotNull
    @Override
    public List<IInfusionTank> getInfusionTanks(@Nullable Direction side) {
        if (hasTransmitterNetwork()) {
            return getTransmitterNetwork().getInfusionTanks(side);
        }
        return infusionTanks;
    }

    @NotNull
    @Override
    public List<IPigmentTank> getPigmentTanks(@Nullable Direction side) {
        if (hasTransmitterNetwork()) {
            return getTransmitterNetwork().getPigmentTanks(side);
        }
        return pigmentTanks;
    }

    @NotNull
    @Override
    public List<ISlurryTank> getSlurryTanks(@Nullable Direction side) {
        if (hasTransmitterNetwork()) {
            return getTransmitterNetwork().getSlurryTanks(side);
        }
        return slurryTanks;
    }

    @Override
    public void onContentsChanged() {
        getTransmitterTile().setChanged();
    }

    @Override
    protected void handleContentsUpdateTag(@NotNull BoxedChemicalNetwork network, @NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider) {
        super.handleContentsUpdateTag(network, tag, provider);
        NBTUtils.setFloatIfPresent(tag, NBTConstants.SCALE, scale -> network.currentScale = scale);
        NBTUtils.setBoxedChemicalIfPresent(provider, tag, NBTConstants.BOXED_CHEMICAL, network::setLastChemical);
    }

    public IGasTank getGasTank() {
        return chemicalTank.getGasTank();
    }

    public IInfusionTank getInfusionTank() {
        return chemicalTank.getInfusionTank();
    }

    public IPigmentTank getPigmentTank() {
        return chemicalTank.getPigmentTank();
    }

    public ISlurryTank getSlurryTank() {
        return chemicalTank.getSlurryTank();
    }
}