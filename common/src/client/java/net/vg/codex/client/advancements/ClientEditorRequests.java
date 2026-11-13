package net.vg.codex.client.advancements;

import dev.architectury.networking.NetworkManager;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.vg.codex.network.OpenAdvancementEditorPayload;
import net.vg.codex.network.RequestAdvancementSnapshotPayload;
import net.vg.codex.network.SaveAdvancementResultPayload;

/** Replies belong to their requesting editor, including while a child screen is open. */
public final class ClientEditorRequests {
    private static final ArrayDeque<Request> snapshots = new ArrayDeque<>();
    private static final ArrayDeque<Request> saves = new ArrayDeque<>();

    private ClientEditorRequests() { }

    private static final class Request {
        final WeakReference<CodexAdvancementsScreen> editor;
        final WeakReference<ClientPacketListener> connection;
        boolean cancelled;

        Request(CodexAdvancementsScreen editor, ClientPacketListener connection) {
            this.editor = new WeakReference<>(editor);
            this.connection = new WeakReference<>(connection);
        }
    }

    private static void prune() {
        var connection = Minecraft.getInstance().getConnection();
        snapshots.removeIf(request -> connection == null || request.connection.get() != connection);
        saves.removeIf(request -> connection == null || request.connection.get() != connection);
    }

    public static void requestSnapshot(CodexAdvancementsScreen editor) {
        prune();
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || editor.isEditorSessionClosed()) return;
        snapshots.addLast(new Request(editor, connection));
        NetworkManager.sendToServer(new RequestAdvancementSnapshotPayload());
    }

    public static void expectSave(CodexAdvancementsScreen editor) {
        prune();
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) saves.addLast(new Request(editor, connection));
    }

    public static void cancelSnapshots(CodexAdvancementsScreen editor) {
        // Keep cancelled entries until their ordered reply arrives, so it cannot open a new screen.
        for (var request : snapshots) {
            if (request.editor.get() == editor) request.cancelled = true;
        }
    }

    /** False denotes an unsolicited command response, which may legitimately open the editor. */
    public static boolean snapshot(OpenAdvancementEditorPayload payload) {
        prune();
        if (Minecraft.getInstance().getConnection() == null) return true;
        var request = snapshots.pollFirst();
        if (request == null) return false;
        var editor = request.editor.get();
        if (!request.cancelled && editor != null && !editor.isEditorSessionClosed()) {
            editor.enterEditMode(payload.advancements(), payload.progress());
        }
        return true;
    }

    public static void saved(SaveAdvancementResultPayload payload) {
        prune();
        if (Minecraft.getInstance().getConnection() == null) return;
        var request = saves.pollFirst();
        if (request != null) {
            var editor = request.editor.get();
            if (editor != null && !editor.isEditorSessionClosed()) {
                editor.onSaveConfirmed(payload.saved(), payload.failed());
            }
        } else if (Minecraft.getInstance().screen instanceof CodexAdvancementsScreen editor) {
            editor.onSaveConfirmed(payload.saved(), payload.failed());
        }
    }
}
