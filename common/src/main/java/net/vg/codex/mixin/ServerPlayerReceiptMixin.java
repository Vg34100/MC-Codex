package net.vg.codex.mixin;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.vg.codex.advancement.TransactionReceipt;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerPlayer.class)
abstract class ServerPlayerReceiptMixin implements TransactionReceipt {
    @Unique private String codex$transaction = "";
    public String codex$receipt() { return codex$transaction; }
    public void codex$receipt(String receipt) { codex$transaction = receipt; }
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void codex$read(ValueInput input, CallbackInfo ci) { codex$transaction = input.getStringOr("codex_transaction", ""); }
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void codex$write(ValueOutput output, CallbackInfo ci) { if (!codex$transaction.isEmpty()) output.putString("codex_transaction", codex$transaction); }
}
