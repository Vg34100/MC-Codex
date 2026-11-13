package net.vg.codex.advancement;
/** Saved in the same vanilla player document as the inventory and XP. */
public interface TransactionReceipt {
    String codex$receipt();
    void codex$receipt(String receipt);
}
