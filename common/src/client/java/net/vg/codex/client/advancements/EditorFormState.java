package net.vg.codex.client.advancements;

import net.vg.codex.advancement.AdvancementDocument.Requirements;

/** Unsaved form fields survive picker navigation, widget rebuilds and GUI scaling. */
record EditorFormState(String title, String description, boolean toast, boolean chat,
                       boolean hidden, boolean claimable, Requirements requirements) { }
