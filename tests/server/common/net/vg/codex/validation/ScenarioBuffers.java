package net.vg.codex.validation;

final class ScenarioBuffers {
    private ScenarioBuffers() { }
    static net.minecraft.network.RegistryFriendlyByteBuf create(io.netty.buffer.ByteBuf data,net.minecraft.core.RegistryAccess registries) {
        //? if <1.21 {
        /*return new net.minecraft.network.FriendlyByteBuf(data);
        *///?} else {
        return new net.minecraft.network.RegistryFriendlyByteBuf(data,registries);
        //?}
    }
}
