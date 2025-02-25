package mekanism.common.capabilities.fluid;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.NBTConstants;
import mekanism.api.annotations.NothingNullByDefault;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.functions.ConstantPredicates;
import mekanism.common.util.NBTUtils;
import mekanism.common.util.RegistryUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@NothingNullByDefault
public class BasicFluidTank implements IExtendedFluidTank {

    public static final Predicate<@NotNull FluidStack> alwaysTrue = ConstantPredicates.alwaysTrue();
    public static final Predicate<@NotNull FluidStack> alwaysFalse = ConstantPredicates.alwaysFalse();
    public static final BiPredicate<@NotNull FluidStack, @NotNull AutomationType> alwaysTrueBi = ConstantPredicates.alwaysTrueBi();
    public static final BiPredicate<@NotNull FluidStack, @NotNull AutomationType> internalOnly = ConstantPredicates.internalOnly();
    public static final BiPredicate<@NotNull FluidStack, @NotNull AutomationType> notExternal = ConstantPredicates.notExternal();
    public static final BiPredicate<@NotNull FluidStack, @NotNull AutomationType> manualOnly = (fluid, automationType) -> automationType == AutomationType.MANUAL;

    public static BasicFluidTank create(int capacity, @Nullable IContentsListener listener) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be at least zero");
        }
        return new BasicFluidTank(capacity, alwaysTrueBi, alwaysTrueBi, alwaysTrue, listener);
    }

    public static BasicFluidTank create(int capacity, Predicate<@NotNull FluidStack> validator, @Nullable IContentsListener listener) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be at least zero");
        }
        Objects.requireNonNull(validator, "Fluid validity check cannot be null");
        return new BasicFluidTank(capacity, alwaysTrueBi, alwaysTrueBi, validator, listener);
    }

    public static BasicFluidTank create(int capacity, Predicate<@NotNull FluidStack> canExtract, Predicate<@NotNull FluidStack> canInsert,
          @Nullable IContentsListener listener) {
        return create(capacity, canExtract, canInsert, alwaysTrue, listener);
    }

    public static BasicFluidTank input(int capacity, Predicate<@NotNull FluidStack> validator, @Nullable IContentsListener listener) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be at least zero");
        }
        Objects.requireNonNull(validator, "Fluid validity check cannot be null");
        return new BasicFluidTank(capacity, notExternal, alwaysTrueBi, validator, listener);
    }

    public static BasicFluidTank input(int capacity, Predicate<@NotNull FluidStack> canInsert, Predicate<@NotNull FluidStack> validator, @Nullable IContentsListener listener) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be at least zero");
        }
        Objects.requireNonNull(canInsert, "Insertion validity check cannot be null");
        Objects.requireNonNull(validator, "Fluid validity check cannot be null");
        return new BasicFluidTank(capacity, notExternal, (stack, automationType) -> canInsert.test(stack), validator, listener);
    }

    public static BasicFluidTank output(int capacity, @Nullable IContentsListener listener) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be at least zero");
        }
        return new BasicFluidTank(capacity, alwaysTrueBi, internalOnly, alwaysTrue, listener);
    }

    public static BasicFluidTank create(int capacity, Predicate<@NotNull FluidStack> canExtract, Predicate<@NotNull FluidStack> canInsert,
          Predicate<@NotNull FluidStack> validator, @Nullable IContentsListener listener) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be at least zero");
        }
        Objects.requireNonNull(canExtract, "Extraction validity check cannot be null");
        Objects.requireNonNull(canInsert, "Insertion validity check cannot be null");
        Objects.requireNonNull(validator, "Fluid validity check cannot be null");
        return new BasicFluidTank(capacity, canExtract, canInsert, validator, listener);
    }

    public static BasicFluidTank create(int capacity, BiPredicate<@NotNull FluidStack, @NotNull AutomationType> canExtract,
          BiPredicate<@NotNull FluidStack, @NotNull AutomationType> canInsert, Predicate<@NotNull FluidStack> validator, @Nullable IContentsListener listener) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be at least zero");
        }
        Objects.requireNonNull(canExtract, "Extraction validity check cannot be null");
        Objects.requireNonNull(canInsert, "Insertion validity check cannot be null");
        Objects.requireNonNull(validator, "Fluid validity check cannot be null");
        return new BasicFluidTank(capacity, canExtract, canInsert, validator, listener);
    }

    /**
     * @apiNote This is only protected for direct querying access. To modify this stack the external methods or {@link #setStackUnchecked(FluidStack)} should be used
     * instead.
     */
    protected FluidStack stored = FluidStack.EMPTY;
    private final Predicate<@NotNull FluidStack> validator;
    protected final BiPredicate<@NotNull FluidStack, @NotNull AutomationType> canExtract;
    protected final BiPredicate<@NotNull FluidStack, @NotNull AutomationType> canInsert;
    private final int capacity;
    @Nullable
    private final IContentsListener listener;

    protected BasicFluidTank(int capacity, Predicate<@NotNull FluidStack> canExtract, Predicate<@NotNull FluidStack> canInsert, Predicate<@NotNull FluidStack> validator,
          @Nullable IContentsListener listener) {
        this(capacity, (stack, automationType) -> automationType == AutomationType.MANUAL || canExtract.test(stack), (stack, automationType) -> canInsert.test(stack),
              validator, listener);
    }

    protected BasicFluidTank(int capacity, BiPredicate<@NotNull FluidStack, @NotNull AutomationType> canExtract,
          BiPredicate<@NotNull FluidStack, @NotNull AutomationType> canInsert, Predicate<@NotNull FluidStack> validator, @Nullable IContentsListener listener) {
        this.capacity = capacity;
        this.canExtract = canExtract;
        this.canInsert = canInsert;
        this.validator = validator;
        this.listener = listener;
    }

    @Override
    public void onContentsChanged() {
        if (listener != null) {
            listener.onContentsChanged();
        }
    }

    @NotNull
    @Override
    public FluidStack getFluid() {
        return stored;
    }

    @Override
    public void setStack(FluidStack stack) {
        setStack(stack, true);
    }

    /**
     * Helper method to allow easily setting a rate at which this {@link BasicFluidTank} can insert/extract fluids.
     *
     * @param automationType The automation type to limit the rate by or null if we don't have access to an automation type.
     *
     * @return The rate this tank can insert/extract at.
     *
     * @implNote By default, this returns {@link Integer#MAX_VALUE} to not actually limit the tank's rate. By default, this is also ignored for direct setting of the
     * stack/stack size
     */
    protected int getRate(@Nullable AutomationType automationType) {
        //TODO - 1.20.5: As we don't actually use this anymore in subclasses decide if we want to remove this
        // That or we might want to actually start making use of it
        //TODO: Decide if we want to split this into a rate for inserting and a rate for extracting.
        return Integer.MAX_VALUE;
    }

    @Override
    public void setStackUnchecked(FluidStack stack) {
        setStack(stack, false);
    }

    private void setStack(FluidStack stack, boolean validateStack) {
        if (stack.isEmpty()) {
            if (stored.isEmpty()) {
                //If we are already empty just exit, to not fire onContentsChanged
                return;
            }
            stored = FluidStack.EMPTY;
        } else if (!validateStack || isFluidValid(stack)) {
            stored = stack.copy();
        } else {
            //Throws a RuntimeException as specified is allowed when something unexpected happens
            // As setStack is more meant to be used as an internal method
            throw new RuntimeException("Invalid fluid for tank: " + RegistryUtils.getName(stack.getFluid()) + " " + stack.getAmount());
        }
        onContentsChanged();
    }

    @Override
    public FluidStack insert(@NotNull FluidStack stack, Action action, AutomationType automationType) {
        if (stack.isEmpty() || !isFluidValid(stack) || !canInsert.test(stack, automationType)) {
            //"Fail quick" if the given stack is empty, or we can never insert the fluid or currently are unable to insert it
            return stack;
        }
        int needed = Math.min(getRate(automationType), getNeeded());
        if (needed <= 0) {
            //Fail if we are a full tank or our rate is zero
            return stack;
        }
        boolean sameType = false;
        if (isEmpty() || (sameType = isFluidEqual(stack))) {
            int toAdd = Math.min(stack.getAmount(), needed);
            if (action.execute()) {
                //If we want to actually insert the fluid, then update the current fluid
                if (sameType) {
                    //We can just grow our stack by the amount we want to increase it
                    stored.grow(toAdd);
                    onContentsChanged();
                } else {
                    //If we are not the same type then we have to copy the stack and set it
                    // Just set it unchecked as we have already validated it
                    // Note: this also will mark that the contents changed
                    setStackUnchecked(stack.copyWithAmount(toAdd));
                }
            }
            return stack.copyWithAmount(stack.getAmount() - toAdd);
        }
        //If we didn't accept this fluid, then just return the given stack
        return stack;
    }

    @Override
    public FluidStack extract(int amount, Action action, AutomationType automationType) {
        if (isEmpty() || amount < 1 || !canExtract.test(stored, automationType)) {
            //"Fail quick" if we don't can never extract from this tank, have a fluid stored, or the amount being requested is less than one
            return FluidStack.EMPTY;
        }
        //Note: While we technically could just return the stack itself if we are removing all that we have, it would require a lot more checks
        // We also are limiting it by the rate this tank has
        int size = Math.min(Math.min(getRate(automationType), getFluidAmount()), amount);
        FluidStack ret = stored.copyWithAmount(size);
        if (!ret.isEmpty() && action.execute()) {
            //If shrink gets the size to zero it will update the empty state so that isEmpty() returns true.
            stored.shrink(ret.getAmount());
            onContentsChanged();
        }
        return ret;
    }

    @Override
    public boolean isFluidValid(FluidStack stack) {
        return validator.test(stack);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Overwritten so that if we decide to change to returning a cached/copy of our stack in {@link #getFluid()}, we can optimize out the copying, and can also
     * directly modify our stack instead of having to make a copy.
     */
    @Override
    public int setStackSize(int amount, Action action) {
        if (isEmpty()) {
            return 0;
        } else if (amount <= 0) {
            if (action.execute()) {
                setEmpty();
            }
            return 0;
        }
        int maxStackSize = getCapacity();
        if (amount > maxStackSize) {
            amount = maxStackSize;
        }
        if (getFluidAmount() == amount || action.simulate()) {
            //If our size is not changing, or we are only simulating the change, don't do anything
            return amount;
        }
        stored.setAmount(amount);
        onContentsChanged();
        return amount;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Overwritten so that we can make this obey the rate limit our tank may have
     */
    @Override
    public int growStack(int amount, Action action) {
        int current = getFluidAmount();
        if (amount > 0) {
            //Cap adding amount at how much we need, so that we don't risk integer overflow
            amount = Math.min(Math.min(amount, getNeeded()), getRate(null));
        } else if (amount < 0) {
            amount = Math.max(amount, -getRate(null));
        }
        int newSize = setStackSize(current + amount, action);
        return newSize - current;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Overwritten so that if we decide to change to returning a cached/copy of our stack in {@link #getFluid()}, we can optimize out the copying.
     */
    @Override
    public boolean isEmpty() {
        return stored.isEmpty();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Overwritten so that if we decide to change to returning a cached/copy of our stack in {@link #getFluid()}, we can optimize out the copying.
     */
    @Override
    public boolean isFluidEqual(FluidStack other) {
        return FluidStack.isSameFluidSameComponents(stored, other);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Overwritten so that if we decide to change to returning a cached/copy of our stack in {@link #getFluid()}, we can optimize out the copying.
     */
    @Override
    public int getFluidAmount() {
        return stored.getAmount();
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Overwritten so that if we decide to change to returning a cached/copy of our stack in {@link #getFluid()}, we can optimize out the copying.
     */
    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag nbt = new CompoundTag();
        if (!isEmpty()) {
            nbt.put(NBTConstants.STORED, stored.save(provider));
        }
        return nbt;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        NBTUtils.setFluidStackIfPresent(provider, nbt, NBTConstants.STORED, this::setStackUnchecked);
    }
}