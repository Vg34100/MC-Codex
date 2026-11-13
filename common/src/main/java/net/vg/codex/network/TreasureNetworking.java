package net.vg.codex.network;

import com.google.gson.JsonParser;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.vg.codex.advancement.AdvancementPackSync;
import net.vg.codex.advancement.TreasureLists;
import java.util.Set;
import java.util.stream.Collectors;

public final class TreasureNetworking {
    private TreasureNetworking() { }

    public static void register() {
        if (Platform.getEnvironment() == Env.SERVER) NetworkManager.registerS2CPayloadType(TreasureListsPayload.TYPE, TreasureListsPayload.STREAM_CODEC);
        NetworkManager.registerReceiver(NetworkManager.c2s(), SaveTreasureListPayload.TYPE, SaveTreasureListPayload.STREAM_CODEC, (payload, context) -> context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player) || !allowed(player)) return;
            try {
                TreasureLists.save(AdvancementPackSync.externalPackDir(), payload.id(), JsonParser.parseString(payload.definition()).getAsJsonObject(), lootTables(player));
                AdvancementPackSync.reloadAfterEdit(player.level().getServer(), () -> send(player, "Treasure list saved."), () -> send(player, "Reload failed; check the server log."));
            } catch (RuntimeException invalid) { send(player, "Rejected: " + invalid.getMessage()); }
        }));
    }

    private static boolean allowed(ServerPlayer player) { return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack()); }

    public static Set<Identifier> lootTables(ServerPlayer player) {
        //? if <26.1 {
        /*return Set.copyOf(player.level().getServer().reloadableRegistries().getKeys(Registries.LOOT_TABLE));
        *///?} else {
        return player.level().getServer().reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).listElementIds().map(key -> key.identifier()).collect(Collectors.toSet());
        //?}
    }

    public static void send(ServerPlayer player, String status) {
        if (!allowed(player)) return;
        NetworkManager.sendToPlayer(player, new TreasureListsPayload(TreasureLists.read(AdvancementPackSync.externalPackDir()).toString(), lootTables(player).stream().sorted().toList(), status.length() > 500 ? status.substring(0, 500) : status));
    }
}
