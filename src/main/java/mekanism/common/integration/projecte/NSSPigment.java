package mekanism.common.integration.projecte;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.pigment.Pigment;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.providers.IPigmentProvider;
import moze_intel.projecte.api.codec.NSSCodecHolder;
import moze_intel.projecte.api.nss.AbstractNSSTag;
import moze_intel.projecte.api.nss.NormalizedSimpleStack;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.NotNull;

/**
 * Implementation of {@link NormalizedSimpleStack} and {@link moze_intel.projecte.api.nss.NSSTag} for representing {@link Pigment}s.
 */
public final class NSSPigment extends AbstractNSSTag<Pigment> {

    private static final boolean ALLOW_DEFAULT = false;

    /**
     * Codec for encoding NSSPigments to and from strings.
     */
    public static final Codec<NSSPigment> LEGACY_CODEC = createLegacyCodec(MekanismAPI.PIGMENT_REGISTRY, ALLOW_DEFAULT, "PIGMENT|", NSSPigment::new);

    public static final MapCodec<NSSPigment> EXPLICIT_MAP_CODEC = createExplicitCodec(MekanismAPI.PIGMENT_REGISTRY, ALLOW_DEFAULT, NSSPigment::new);
    public static final Codec<NSSPigment> EXPLICIT_CODEC = EXPLICIT_MAP_CODEC.codec();

    public static final NSSCodecHolder<NSSPigment> CODECS = new NSSCodecHolder<>("PIGMENT", LEGACY_CODEC, EXPLICIT_CODEC);

    private NSSPigment(@NotNull ResourceLocation resourceLocation, boolean isTag) {
        super(resourceLocation, isTag);
    }

    /**
     * Helper method to create an {@link NSSPigment} representing a pigment type from a {@link PigmentStack}
     */
    @NotNull
    public static NSSPigment createPigment(@NotNull PigmentStack stack) {
        //Don't bother checking if it is empty as getType returns EMPTY which will then fail anyway for being empty
        return createPigment(stack.getChemical());
    }

    /**
     * Helper method to create an {@link NSSPigment} representing a pigment type from a {@link IPigmentProvider}
     */
    @NotNull
    public static NSSPigment createPigment(@NotNull IPigmentProvider pigmentProvider) {
        return createPigment(pigmentProvider.getChemical());
    }

    /**
     * Helper method to create an {@link NSSPigment} representing a pigment type from a {@link Pigment}
     */
    @NotNull
    public static NSSPigment createPigment(@NotNull Pigment pigment) {
        if (pigment.isEmptyType()) {
            throw new IllegalArgumentException("Can't make NSSPigment with an empty pigment");
        }
        //This should never be null, or it would have crashed on being registered
        return createPigment(pigment.getRegistryName());
    }

    /**
     * Helper method to create an {@link NSSPigment} representing a pigment type from a {@link ResourceLocation}
     */
    @NotNull
    public static NSSPigment createPigment(@NotNull ResourceLocation pigmentID) {
        return new NSSPigment(pigmentID, false);
    }

    /**
     * Helper method to create an {@link NSSPigment} representing a tag from a {@link ResourceLocation}
     */
    @NotNull
    public static NSSPigment createTag(@NotNull ResourceLocation tagId) {
        return new NSSPigment(tagId, true);
    }

    /**
     * Helper method to create an {@link NSSPigment} representing a tag from a {@link TagKey<Pigment>}
     */
    @NotNull
    public static NSSPigment createTag(@NotNull TagKey<Pigment> tag) {
        return createTag(tag.location());
    }

    @NotNull
    @Override
    protected Registry<Pigment> getRegistry() {
        return MekanismAPI.PIGMENT_REGISTRY;
    }

    @Override
    protected NSSPigment createNew(Pigment pigment) {
        return createPigment(pigment);
    }

    @Override
    public NSSCodecHolder<NSSPigment> codecs() {
        return CODECS;
    }
}