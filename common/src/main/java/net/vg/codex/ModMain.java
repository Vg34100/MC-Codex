package net.vg.codex;

import dev.architectury.event.events.common.LifecycleEvent;
import net.vg.codex.advancement.AdvancementPackSync;

public final class ModMain {
    public static final String MOD_ID = "codex";

    private ModMain() {
    }

    public static void init() {
        LifecycleEvent.SERVER_STARTING.register(AdvancementPackSync::onServerStarting);
    }
}
