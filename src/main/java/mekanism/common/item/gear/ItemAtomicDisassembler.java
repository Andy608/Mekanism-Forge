package mekanism.common.item.gear;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2BooleanMaps;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IDisableableEnum;
import mekanism.api.annotations.NothingNullByDefault;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.functions.ConstantPredicates;
import mekanism.api.math.FloatingLong;
import mekanism.api.radial.IRadialDataHelper;
import mekanism.api.radial.RadialData;
import mekanism.api.radial.mode.IRadialMode;
import mekanism.api.text.EnumColor;
import mekanism.api.text.IHasTextComponent;
import mekanism.api.text.ILangEntry;
import mekanism.client.render.RenderPropertiesProvider;
import mekanism.common.Mekanism;
import mekanism.common.MekanismLang;
import mekanism.common.config.MekanismConfig;
import mekanism.common.content.gear.mekatool.ModuleExcavationEscalationUnit.ExcavationMode;
import mekanism.common.content.gear.mekatool.ModuleVeinMiningUnit;
import mekanism.common.item.ItemEnergized;
import mekanism.common.item.gear.ItemAtomicDisassembler.DisassemblerMode;
import mekanism.common.item.interfaces.IItemHUDProvider;
import mekanism.common.lib.attribute.AttributeCache;
import mekanism.common.lib.attribute.IAttributeRefresher;
import mekanism.common.lib.radial.IRadialModeItem;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.registries.MekanismItems;
import mekanism.common.tags.MekanismTags;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.StorageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.common.ToolAction;
import net.neoforged.neoforge.common.ToolActions;
import net.neoforged.neoforge.common.util.Lazy;
import net.neoforged.neoforge.registries.holdersets.AnyHolderSet;
import org.jetbrains.annotations.NotNull;

public class ItemAtomicDisassembler extends ItemEnergized implements IItemHUDProvider, IRadialModeItem<DisassemblerMode>, IAttributeRefresher {

    //All basic dig actions except shears
    public static final Set<ToolAction> ALWAYS_SUPPORTED_ACTIONS = Set.of(ToolActions.AXE_DIG, ToolActions.HOE_DIG, ToolActions.SHOVEL_DIG, ToolActions.PICKAXE_DIG,
          ToolActions.SWORD_DIG);
    private static final Lazy<RadialData<DisassemblerMode>> LAZY_RADIAL_DATA = Lazy.of(() ->
          IRadialDataHelper.INSTANCE.dataForEnum(Mekanism.rl("disassembler_mode"), DisassemblerMode.NORMAL));

    /**
     * @apiNote For use in calculating drops of given blocks. Given mods may do checks relating to tool actions we need to make sure that this stack is full energy.
     */
    public static ItemStack fullyChargedStack() {
        return StorageUtils.getFilledEnergyVariant(MekanismItems.ATOMIC_DISASSEMBLER.getItemStack());
    }

    private final AttributeCache attributeCache;

    public ItemAtomicDisassembler(Properties properties) {
        super(properties.rarity(Rarity.RARE).setNoRepair().stacksTo(1)
              .component(MekanismDataComponents.DISASSEMBLER_MODE, DisassemblerMode.NORMAL)
              .component(DataComponents.TOOL, new Tool(List.of(
                    Tool.Rule.deniesDrops(MekanismTags.Blocks.INCORRECT_FOR_DISASSEMBLER),
                    new Tool.Rule(new AnyHolderSet<>(BuiltInRegistries.BLOCK.asLookup()), Optional.empty(), Optional.of(true))
              ), 1, 0))
        );
        this.attributeCache = new AttributeCache(this, MekanismConfig.gear.disassemblerMaxDamage, MekanismConfig.gear.disassemblerAttackSpeed);
    }

    @Override
    public void initializeClient(@NotNull Consumer<IClientItemExtensions> consumer) {
        consumer.accept(RenderPropertiesProvider.disassembler());
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull Item.TooltipContext context, @NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        DisassemblerMode mode = getMode(stack);
        tooltip.add(MekanismLang.MODE.translateColored(EnumColor.INDIGO, mode));
        tooltip.add(MekanismLang.DISASSEMBLER_EFFICIENCY.translateColored(EnumColor.INDIGO, mode.getEfficiency()));
    }

    @Override
    public boolean canPerformAction(ItemStack stack, ToolAction action) {
        if (ALWAYS_SUPPORTED_ACTIONS.contains(action)) {
            IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
            if (energyContainer != null) {
                //Note: We use a hardness of zero here as that will get the minimum potential destroy energy required
                // as that is the best guess we can currently give whether the corresponding dig action is supported
                FloatingLong energyRequired = getDestroyEnergy(stack, 0);
                FloatingLong energyAvailable = energyContainer.getEnergy();
                //If we don't have enough energy to break at full speed check if the reduced speed could actually mine
                return energyRequired.smallerOrEqual(energyAvailable) || !energyAvailable.divide(energyRequired).isZero();
            }
        }
        return false;
    }

    @Override
    public boolean hurtEnemy(@NotNull ItemStack stack, @NotNull LivingEntity target, @NotNull LivingEntity attacker) {
        IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
        if (energyContainer != null && !energyContainer.isEmpty()) {
            //Try to extract full energy, even if we have a lower damage amount this is fine as that just means
            // we don't have enough energy, but we will remove as much as we can, which is how much corresponds
            // to the amount of damage we will actually do
            energyContainer.extract(MekanismConfig.gear.disassemblerEnergyUsageWeapon.get(), Action.EXECUTE, AutomationType.MANUAL);
        }
        return true;
    }

    @Override
    public float getDestroySpeed(@NotNull ItemStack stack, @NotNull BlockState state) {
        IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
        if (energyContainer == null) {
            return 0;
        }
        //Use raw hardness to get the best guess of if it is zero or not
        FloatingLong energyRequired = getDestroyEnergy(stack, state.destroySpeed);
        FloatingLong energyAvailable = energyContainer.extract(energyRequired, Action.SIMULATE, AutomationType.MANUAL);
        if (energyAvailable.smallerThan(energyRequired)) {
            //If we can't extract all the energy we need to break it go at base speed reduced by how much we actually have available
            return DisassemblerMode.NORMAL.getEfficiency() * energyAvailable.divide(energyRequired).floatValue();
        }
        return getMode(stack).getEfficiency();
    }

    @Override
    public boolean mineBlock(@NotNull ItemStack stack, @NotNull Level world, @NotNull BlockState state, @NotNull BlockPos pos, @NotNull LivingEntity entityliving) {
        IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
        if (energyContainer != null) {
            energyContainer.extract(getDestroyEnergy(stack, state.getDestroySpeed(world, pos)), Action.EXECUTE, AutomationType.MANUAL);
        }
        return true;
    }

    @Override
    public boolean onBlockStartBreak(ItemStack stack, BlockPos pos, Player player) {
        if (player.level().isClientSide || player.isCreative()) {
            return super.onBlockStartBreak(stack, pos, player);
        }
        IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
        if (energyContainer != null && getMode(stack) == DisassemblerMode.VEIN) {
            Level world = player.level();
            BlockState state = world.getBlockState(pos);
            FloatingLong baseDestroyEnergy = getDestroyEnergy(stack);
            FloatingLong energyRequired = getDestroyEnergy(baseDestroyEnergy, state.getDestroySpeed(world, pos));
            if (energyContainer.extract(energyRequired, Action.SIMULATE, AutomationType.MANUAL).greaterOrEqual(energyRequired)) {
                // Only allow mining things that are considered an ore
                if (ModuleVeinMiningUnit.canVeinBlock(state) && state.is(MekanismTags.Blocks.ATOMIC_DISASSEMBLER_ORE)) {
                    Object2IntMap<BlockPos> found = ModuleVeinMiningUnit.findPositions(world, Map.of(pos, state), 0,
                          Reference2BooleanMaps.singleton(state.getBlock(), true));
                    MekanismUtils.veinMineArea(energyContainer, energyRequired, FloatingLong.ZERO, baseDestroyEnergy, world, pos, (ServerPlayer) player, stack,
                          this, found, (base, hardness) -> FloatingLong.ZERO,
                          (base, hardness, distance, bs) -> getDestroyEnergy(base, hardness).multiply(0.5 * Math.pow(distance, 1.5)));
                }
            }
        }
        return super.onBlockStartBreak(stack, pos, player);
    }

    private FloatingLong getDestroyEnergy(ItemStack itemStack, float hardness) {
        return getDestroyEnergy(getDestroyEnergy(itemStack), hardness);
    }

    private static FloatingLong getDestroyEnergy(FloatingLong baseDestroyEnergy, float hardness) {
        return hardness == 0 ? baseDestroyEnergy.divide(2) : baseDestroyEnergy;
    }

    private FloatingLong getDestroyEnergy(ItemStack itemStack) {
        return MekanismConfig.gear.disassemblerEnergyUsage.get().multiply(getMode(itemStack).getEfficiency());
    }

    @Override
    public DataComponentType<DisassemblerMode> getModeDataType() {
        return MekanismDataComponents.DISASSEMBLER_MODE.get();
    }

    @Override
    public DisassemblerMode getDefaultMode() {
        return DisassemblerMode.NORMAL;
    }

    @NotNull
    @Override
    public RadialData<DisassemblerMode> getRadialData(ItemStack stack) {
        return LAZY_RADIAL_DATA.get();
    }

    @NotNull
    @Override
    public ItemAttributeModifiers getAttributeModifiers(@NotNull ItemStack stack) {
        IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
        FloatingLong energy = energyContainer == null ? FloatingLong.ZERO : energyContainer.getEnergy();
        FloatingLong energyCost = MekanismConfig.gear.disassemblerEnergyUsageWeapon.get();
        if (energy.greaterOrEqual(energyCost)) {
            //If we have enough energy to act at full damage, use the cached multimap rather than creating a new one
            // This will be the case the vast majority of the time
            return attributeCache.get();
        }
        //If we don't have enough power use it at a reduced power level
        int minDamage = MekanismConfig.gear.disassemblerMinDamage.get();
        int damageDifference = MekanismConfig.gear.disassemblerMaxDamage.get() - minDamage;
        double damage = minDamage + damageDifference * energy.divideToLevel(energyCost);
        ImmutableList.Builder<ItemAttributeModifiers.Entry> builder = ImmutableList.builder();
        builder.add(new ItemAttributeModifiers.Entry(
              Attributes.ATTACK_DAMAGE,
              new AttributeModifier(BASE_ATTACK_DAMAGE_UUID, "Weapon modifier", damage, Operation.ADD_VALUE),
              EquipmentSlotGroup.MAINHAND
        ));
        builder.add(new ItemAttributeModifiers.Entry(
              Attributes.ATTACK_SPEED,
              new AttributeModifier(BASE_ATTACK_SPEED_UUID, "Weapon modifier", MekanismConfig.gear.disassemblerAttackSpeed.get(), Operation.ADD_VALUE),
              EquipmentSlotGroup.MAINHAND
        ));
        return new ItemAttributeModifiers(builder.build(), true);
    }

    @Override
    public void addToBuilder(List<ItemAttributeModifiers.Entry> builder) {
        builder.add(new ItemAttributeModifiers.Entry(
              Attributes.ATTACK_DAMAGE,
              new AttributeModifier(BASE_ATTACK_DAMAGE_UUID, "Weapon modifier", MekanismConfig.gear.disassemblerMaxDamage.get(), Operation.ADD_VALUE),
              EquipmentSlotGroup.MAINHAND
        ));
        builder.add(new ItemAttributeModifiers.Entry(
              Attributes.ATTACK_SPEED,
              new AttributeModifier(BASE_ATTACK_SPEED_UUID, "Weapon modifier", MekanismConfig.gear.disassemblerAttackSpeed.get(), Operation.ADD_VALUE),
              EquipmentSlotGroup.MAINHAND
        ));
    }

    @Override
    public void addHUDStrings(List<Component> list, Player player, ItemStack stack, EquipmentSlot slotType) {
        DisassemblerMode mode = getMode(stack);
        list.add(MekanismLang.MODE.translateColored(EnumColor.GRAY, EnumColor.INDIGO, mode));
        list.add(MekanismLang.DISASSEMBLER_EFFICIENCY.translateColored(EnumColor.GRAY, EnumColor.INDIGO, mode.getEfficiency()));
    }

    @Override
    public void changeMode(@NotNull Player player, @NotNull ItemStack stack, int shift, DisplayChange displayChange) {
        DisassemblerMode mode = getMode(stack);
        DisassemblerMode newMode = mode.adjust(shift);
        if (mode != newMode) {
            setMode(stack, player, newMode);
            displayChange.sendMessage(player, newMode, m -> MekanismLang.DISASSEMBLER_MODE_CHANGE.translate(EnumColor.INDIGO, m, EnumColor.AQUA, m.getEfficiency()));
        }
    }

    @NotNull
    @Override
    public Component getScrollTextComponent(@NotNull ItemStack stack) {
        DisassemblerMode mode = getMode(stack);
        return MekanismLang.GENERIC_WITH_PARENTHESIS.translateColored(EnumColor.INDIGO, mode, EnumColor.AQUA, mode.getEfficiency());
    }

    @Override
    public boolean isEnchantable(@NotNull ItemStack stack) {
        return false;
    }

    @Override
    public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
        return false;
    }

    @Override
    public boolean canApplyAtEnchantingTable(ItemStack stack, Enchantment enchantment) {
        return false;
    }

    @NothingNullByDefault
    public enum DisassemblerMode implements IDisableableEnum<DisassemblerMode>, IHasTextComponent, IRadialMode, StringRepresentable {
        NORMAL(MekanismLang.RADIAL_EXCAVATION_SPEED_NORMAL, 20, ConstantPredicates.ALWAYS_TRUE, EnumColor.BRIGHT_GREEN, ExcavationMode.NORMAL.icon()),
        SLOW(MekanismLang.RADIAL_EXCAVATION_SPEED_SLOW, 8, MekanismConfig.gear.disassemblerSlowMode, EnumColor.PINK, ExcavationMode.SLOW.icon()),
        //Note: Uses extreme icon as both are efficiency 128
        FAST(MekanismLang.RADIAL_EXCAVATION_SPEED_FAST, 128, MekanismConfig.gear.disassemblerFastMode, EnumColor.RED, ExcavationMode.EXTREME.icon()),
        VEIN(MekanismLang.RADIAL_VEIN_NORMAL, 20, MekanismConfig.gear.disassemblerVeinMining, EnumColor.AQUA, MekanismUtils.getResource(MekanismUtils.ResourceType.GUI_RADIAL, "vein_normal.png")),
        OFF(MekanismLang.RADIAL_EXCAVATION_SPEED_OFF, 0, ConstantPredicates.ALWAYS_TRUE, EnumColor.WHITE, ExcavationMode.OFF.icon());

        //We only allow deserializing to enabled modes
        public static final Codec<DisassemblerMode> CODEC = StringRepresentable.fromEnum(DisassemblerMode::values)
              .xmap(mode -> mode.isEnabled() ? mode : DisassemblerMode.NORMAL, Function.identity());
        public static final IntFunction<DisassemblerMode> BY_ID = ByIdMap.continuous(DisassemblerMode::ordinal, values(), ByIdMap.OutOfBoundsStrategy.WRAP);
        //Though we allow network handling to sync it whether it is enabled or not
        public static final StreamCodec<ByteBuf, DisassemblerMode> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, DisassemblerMode::ordinal);

        private final String serializedName;
        private final BooleanSupplier checkEnabled;
        private final ILangEntry langEntry;
        private final int efficiency;
        private final EnumColor color;
        private final ResourceLocation icon;

        DisassemblerMode(ILangEntry langEntry, int efficiency, BooleanSupplier checkEnabled, EnumColor color, ResourceLocation icon) {
            this.serializedName = name().toLowerCase(Locale.ROOT);
            this.langEntry = langEntry;
            this.efficiency = efficiency;
            this.checkEnabled = checkEnabled;
            this.color = color;
            this.icon = icon;
        }

        @Override
        public DisassemblerMode byIndex(int index) {
            return BY_ID.apply(index);
        }

        @Override
        public Component getTextComponent() {
            return langEntry.translate(color);
        }

        @NotNull
        @Override
        public Component sliceName() {
            return getTextComponent();
        }

        public int getEfficiency() {
            return efficiency;
        }

        @Override
        public boolean isEnabled() {
            return checkEnabled.getAsBoolean();
        }

        @NotNull
        @Override
        public ResourceLocation icon() {
            return icon;
        }

        @Override
        public EnumColor color() {
            return color;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }
}