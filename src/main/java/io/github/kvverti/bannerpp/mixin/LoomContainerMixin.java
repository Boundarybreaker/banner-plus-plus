package io.github.kvverti.bannerpp.mixin;

import io.github.kvverti.bannerpp.api.LoomPattern;
import io.github.kvverti.bannerpp.api.LoomPatterns;
import io.github.kvverti.bannerpp.api.LoomPatternItem;
import io.github.kvverti.bannerpp.iface.LoomPatternContainer;

import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.BannerPattern;
import net.minecraft.container.Container;
import net.minecraft.container.LoomContainer;
import net.minecraft.container.Property;
import net.minecraft.container.Slot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.DyeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.DyeColor;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LoomContainer.class)
public abstract class LoomContainerMixin extends Container {

    @Shadow @Final private Property selectedPattern;
    @Shadow @Final private Slot bannerSlot;
    @Shadow @Final private Slot dyeSlot;
    @Shadow @Final private Slot patternSlot;
    @Shadow @Final private Slot outputSlot;

    private LoomContainerMixin() {
        super(null, 0);
    }

    @Shadow private native void updateOutputSlot();

    /**
     * When the player clicks on a square that contains a loom pattern,
     * store the negative of the index clicked. This number is
     * -(loomPatternIndex + 1 + BannerPattern.LOOM_APPLICABLE_COUNT).
     */
    @Inject(method = "onButtonClick", at = @At("HEAD"), cancellable = true)
    private void selectBppLoomPatternOnClick(PlayerEntity entity, int clicked, CallbackInfoReturnable<Boolean> info) {
        int vanillaCount = BannerPattern.LOOM_APPLICABLE_COUNT;
        if(clicked > vanillaCount && clicked - (1 + vanillaCount) < LoomPatterns.dyeLoomPatternCount()) {
            selectedPattern.set(-clicked);
            this.updateOutputSlot();
            info.setReturnValue(true);
        }
    }

    /**
     * Trigger the then branch if the selected pattern is a Banner++ loom pattern.
     * We make the condition `selectedPattern.get() < BannerPattern.COUNT - 5`
     * true in this case.
     */
    @Redirect(
        method = "onContentChanged",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/container/Property;get()I"
        )
    )
    private int addBppLoomPatternCondition(Property self) {
        int res = self.get();
        if(res < 0) {
            res = 1;
        }
        return res;
    }

    @ModifyVariable(
        method = "onContentChanged",
        at = @At(value = "LOAD", ordinal = 0),
        ordinal = 0
    )
    private boolean addBppLoomPatternsToFullCond(boolean original) {
        ItemStack banner = this.bannerSlot.getStack();
        return original || BannerBlockEntity.getPatternCount(banner) >= 6;
    }

    /**
     * Set the loom pattern when a loom pattern item is placed in the loom.
     * This injection is at the beginning of the then block after the check
     * for a loom state that should display an item.
     * Relevant bytecode:
     *   110: aload         4
     *   <injection point>
     *   112: invokevirtual #182                // Method net/minecraft/item/ItemStack.isEmpty:()Z
     *   115: ifne          217
     *   118: aload         4
     *   120: invokevirtual #196                // Method net/minecraft/item/ItemStack.getItem:()Lnet/minecraft/item/Item;
     *   123: instanceof    #198                // class net/minecraft/item/BannerPatternItem
     */
    @Inject(
        method = "onContentChanged",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/item/ItemStack;isEmpty()Z",
            ordinal = 4
        )
    )
    private void updateBppContentChanged(CallbackInfo info) {
        ItemStack banner = this.bannerSlot.getStack();
        ItemStack patternStack = this.patternSlot.getStack();
        // only run for special loom patterns
        if(!patternStack.isEmpty() && patternStack.getItem() instanceof LoomPatternItem) {
            boolean overfull = BannerBlockEntity.getPatternCount(banner) >= 6;
            if(!overfull) {
                LoomPattern pattern = ((LoomPatternItem)patternStack.getItem()).getPattern();
                this.selectedPattern.set(-LoomPatterns.getLoomIndex(pattern) - (1 + BannerPattern.LOOM_APPLICABLE_COUNT));
            } else {
                this.selectedPattern.set(0);
            }
        } else if(-this.selectedPattern.get() - (1 + BannerPattern.LOOM_APPLICABLE_COUNT) >= LoomPatterns.dyeLoomPatternCount()) {
            // reset special loom pattern on removal
            this.selectedPattern.set(0);
            this.outputSlot.setStack(ItemStack.EMPTY);
        }
    }

    /**
     * When the output slot is updated, add the loom pattern to the
     * output banner.
     */
    @Inject(method = "updateOutputSlot", at = @At("HEAD"))
    private void addBppLoomPatternToOutput(CallbackInfo info) {
        ItemStack bannerStack = this.bannerSlot.getStack();
        ItemStack dyeStack = this.dyeSlot.getStack();
        if(this.selectedPattern.get() < 0 && !bannerStack.isEmpty() && !dyeStack.isEmpty()) {
            int rawId = -this.selectedPattern.get() - (1 + BannerPattern.LOOM_APPLICABLE_COUNT);
            if(rawId < LoomPatterns.totalLoomPatternCount()) {
                LoomPattern pattern = LoomPatterns.byLoomIndex(rawId);
                DyeColor color = ((DyeItem)dyeStack.getItem()).getColor();
                ItemStack output = bannerStack.copy();
                output.setCount(1);
                CompoundTag beTag = output.getOrCreateSubTag("BlockEntityTag");
                ListTag loomPatterns;
                if(beTag.contains(LoomPatternContainer.NBT_KEY, 9)) {
                    loomPatterns = beTag.getList(LoomPatternContainer.NBT_KEY, 10);
                } else {
                    loomPatterns = new ListTag();
                    beTag.put(LoomPatternContainer.NBT_KEY, loomPatterns);
                }
                int vanillaPatternCount = beTag.getList("Patterns", 10).size();
                CompoundTag patternTag = new CompoundTag();
                patternTag.putString("Pattern", LoomPatterns.REGISTRY.getId(pattern).toString());
                patternTag.putInt("Color", color.getId());
                patternTag.putInt("Index", vanillaPatternCount);
                loomPatterns.add(patternTag);
                if(!ItemStack.areEqualIgnoreDamage(output, this.outputSlot.getStack())) {
                    this.outputSlot.setStack(output);
                }
            }
        }
    }

    /**
     * Attempts transfer of a loom pattern item into the loom's pattern slot.
     * (The vanilla code only attempts this on vanilla banner pattern items)
     * The injection point targets the first instruction in the if-else ladder.
     * Relevant bytecode:
     *   130: getstatic     #188                // Field net/minecraft/item/ItemStack.EMPTY:Lnet/minecraft/item/ItemStack;
     *   133: areturn
     *   --- basic block boundary ---
     *   <injection point>
     *   134: aload         5
     *   136: invokevirtual #196                // Method net/minecraft/item/ItemStack.getItem:()Lnet/minecraft/item/Item;
     *   139: instanceof    #275                // class net/minecraft/item/BannerItem
     *   142: ifeq          175
     */
    @Inject(
        method = "transferSlot",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/item/ItemStack;getItem()Lnet/minecraft/item/Item;",
            ordinal = 0,
            shift = At.Shift.BEFORE
        ),
        cancellable = true
    )
    private void attemptBppPatternItemTransfer(PlayerEntity player, int slotIdx, CallbackInfoReturnable<ItemStack> info) {
        ItemStack stack = this.slotList.get(slotIdx).getStack();
        if(stack.getItem() instanceof LoomPatternItem) {
            if(!this.insertItem(stack, this.patternSlot.id, this.patternSlot.id + 1, false)) {
                info.setReturnValue(ItemStack.EMPTY);
            }
        }
    }
}
