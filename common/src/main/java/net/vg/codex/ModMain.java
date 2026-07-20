package net.vg.codex;

import dev.architectury.event.events.common.LifecycleEvent;
import net.vg.codex.advancement.AdvancementPackSync;
import net.vg.codex.command.AdvancementEditorCommand;
import net.vg.codex.network.AdvancementEditorNetworking;

public final class ModMain {
    public static final String MOD_ID = "codex";

    private ModMain() {
    }

    public static void init() {
        LifecycleEvent.SERVER_STARTING.register(AdvancementPackSync::onServerStarting);
        AdvancementEditorNetworking.registerCommon();
        AdvancementEditorCommand.register();
    }
}
