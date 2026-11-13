package net.vg.codex.party;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.vg.codex.advancement.AdvancementPackSync;
import net.vg.codex.advancement.CodexQuestIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Persistent, small, invite-only parties and vanilla-advancement completion propagation. */
public final class CodexPartyService {
    private static final int MAX_MEMBERS = 8;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, UUID> PENDING_INVITES = new HashMap<>();
    private static final Set<UUID> PROPAGATING = new HashSet<>();
    private static int ticks;

    public static void resetSession() { PENDING_INVITES.clear(); PROPAGATING.clear(); ticks = 0; }

    public static void tick(MinecraftServer server) {
        if (++ticks % 20 != 0) return;
        read(server).forEach((party, members) -> {
            if (members.stream().anyMatch(id -> server.getPlayerList().getPlayer(id) != null)) synchronizeParty(server, party, members);
        });
    }

    private CodexPartyService() {}

    public static void invite(ServerPlayer inviter, ServerPlayer target) {
        if (inviter.getUUID().equals(target.getUUID())) { inviter.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: you cannot invite yourself.")); return; }
        Map<UUID, Set<UUID>> parties = read(inviter.level().getServer());
        if (partyOf(parties, target.getUUID()) != null) { inviter.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: that player is already in a party.")); return; }
        UUID party = partyOf(parties, inviter.getUUID());
        if (party == null) { party = UUID.randomUUID(); parties.put(party, new HashSet<>(Set.of(inviter.getUUID()))); mergeSubmissionProgress(inviter.level().getServer(), party, inviter.getUUID()); write(inviter.level().getServer(), parties); }
        if (parties.get(party).size() >= MAX_MEMBERS) { inviter.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: party is full (8 players).")); return; }
        PENDING_INVITES.put(target.getUUID(), inviter.getUUID());
        target.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: " + inviter.getScoreboardName() + " invited you. Use /codex party accept."));
        inviter.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: invitation sent."));
    }

    public static void accept(ServerPlayer player) {
        UUID inviterId = PENDING_INVITES.remove(player.getUUID());
        if (inviterId == null) { player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: no pending party invitation.")); return; }
        MinecraftServer server = player.level().getServer();
        ServerPlayer inviter = server.getPlayerList().getPlayer(inviterId);
        if (inviter == null) { player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: inviter is no longer online.")); return; }
        Map<UUID, Set<UUID>> parties = read(server);
        UUID party = partyOf(parties, inviterId);
        if (party == null || partyOf(parties, player.getUUID()) != null || parties.get(party).size() >= MAX_MEMBERS) return;
        parties.get(party).add(player.getUUID());
        mergeSubmissionProgress(server, party, player.getUUID());
        write(server, parties);
        for (UUID member : parties.get(party)) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) AdvancementPackSync.sendSubmissionTasksTo(online);
        }
        synchronizeParty(server, party, parties.get(party));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: joined party with " + inviter.getScoreboardName() + "."));
        inviter.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: " + player.getScoreboardName() + " joined the party."));
    }

    public static void leave(ServerPlayer player) {
        MinecraftServer server = player.level().getServer(); Map<UUID, Set<UUID>> parties = read(server); UUID party = partyOf(parties, player.getUUID());
        if (party == null) { player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: you are not in a party.")); return; }
        parties.get(party).remove(player.getUUID()); if (parties.get(party).isEmpty()) parties.remove(party); write(server, parties);
        AdvancementPackSync.sendSubmissionTasksTo(player);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: left the party."));
    }

    public static void onAdvancementAward(ServerPlayer source, AdvancementHolder advancement) {
        MinecraftServer server = source.level().getServer();
        var parties = read(server); UUID party = partyOf(parties, source.getUUID());
        if (party != null) synchronizeParty(server, party, parties.get(party));
    }

    /** Reconcile an existing party whenever one of its members joins. This covers progress earned
     * while teammates were offline, without requiring a new invite or completion event. */
    public static void onJoin(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        UUID party = partyOf(read(server), player.getUUID());
        if (party != null) synchronizeParty(server, party, read(server).get(party));
    }

    /** A party UUID is used as the shared submission-progress owner; solo players use themselves. */
    public static UUID progressOwner(MinecraftServer server, UUID playerId) {
        UUID party = partyOf(read(server), playerId);
        return party != null ? party : playerId;
    }

    /** A defensive membership snapshot for optional shared quest cycles. */
    public static Set<UUID> members(MinecraftServer server, UUID playerId) {
        var parties = read(server); UUID party = partyOf(parties, playerId);
        return party == null ? Set.of(playerId) : Set.copyOf(parties.get(party));
    }

    private static void mergeSubmissionProgress(MinecraftServer server, UUID party, UUID personal) {
        Path pack = net.vg.codex.advancement.QuestState.directory(server);
        Map<String, Map<Identifier, Integer>> shared = CodexQuestIO.readSubmissionProgress(pack, party);
        Map<String, Map<Identifier, Integer>> individual = CodexQuestIO.readSubmissionProgress(pack, personal);
        for (var task : individual.entrySet()) {
            if (task.getKey().startsWith("@import/")) continue;
            Map<Identifier, Integer> target = shared.computeIfAbsent(task.getKey(), ignored -> new HashMap<>());
            Map<Identifier, Integer> imported = shared.computeIfAbsent("@import/" + personal + "/" + task.getKey(), ignored -> new HashMap<>());
            task.getValue().forEach((item, amount) -> {
                int delta = Math.max(0, amount - imported.getOrDefault(item, 0));
                target.merge(item, delta, Math::addExact);
                imported.merge(item, amount, Math::max);
            });
        }
        CodexQuestIO.writeSubmissionProgress(pack, party, shared);
    }

    private static void synchronizeParty(MinecraftServer server, UUID party, Set<UUID> members) {
        if (!PROPAGATING.add(party)) return;
        try {
            Path file = net.vg.codex.advancement.QuestState.directory(server).resolve("codex/party_advancements/" + party + ".json");
            JsonObject ledger = new JsonObject();
            if (Files.exists(file)) try { ledger = JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
            catch (IOException error) { throw new IllegalStateException("Cannot read party advancement progress", error); }
            JsonObject before = ledger.deepCopy();
            for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
                Set<String> earned = new HashSet<>();
                if (ledger.has(holder.id().toString())) for (var value : ledger.getAsJsonArray(holder.id().toString())) earned.add(value.getAsString());
                for (UUID member : members) {
                    ServerPlayer online = server.getPlayerList().getPlayer(member);
                    if (online == null) continue;
                    var progress = online.getAdvancements().getOrStartProgress(holder);
                    for (String criterion : holder.value().criteria().keySet()) {
                        var criterionProgress = progress.getCriterion(criterion);
                        if (criterionProgress != null && criterionProgress.isDone()) earned.add(criterion);
                    }
                }
                if (earned.isEmpty()) continue;
                JsonArray array = new JsonArray(); earned.stream().sorted().forEach(array::add); ledger.add(holder.id().toString(), array);
            }
            // Persist before propagation: an offline teammate must be able to receive it later,
            // even when nobody who originally earned the criterion remains connected.
            if (!before.equals(ledger)) net.vg.codex.advancement.AtomicJson.write(file, ledger);
            for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
                if (!ledger.has(holder.id().toString())) continue;
                for (UUID member : members) {
                    ServerPlayer online = server.getPlayerList().getPlayer(member);
                    if (online == null) continue;
                    for (var value : ledger.getAsJsonArray(holder.id().toString())) {
                        String criterion = value.getAsString();
                        if (holder.value().criteria().containsKey(criterion)) online.getAdvancements().award(holder, criterion);
                    }
                }
            }
        } finally { PROPAGATING.remove(party); }
    }

    private static UUID partyOf(Map<UUID, Set<UUID>> parties, UUID player) { for (var e : parties.entrySet()) if (e.getValue().contains(player)) return e.getKey(); return null; }
    private static Path file(MinecraftServer server) { return net.vg.codex.advancement.QuestState.directory(server).resolve("codex/parties.json"); }
    private static Map<UUID, Set<UUID>> read(MinecraftServer server) { Map<UUID, Set<UUID>> out = new HashMap<>(); Path file=file(server); try { JsonObject root=net.vg.codex.advancement.QuestReadScope.read(file); for(var e:root.entrySet()){Set<UUID> members=new HashSet<>();for(var id:e.getValue().getAsJsonArray())members.add(UUID.fromString(id.getAsString()));out.put(UUID.fromString(e.getKey()),members);} }catch(IOException|RuntimeException error){throw new IllegalStateException("Cannot read party state", error);} return out; }
    private static void write(MinecraftServer server, Map<UUID, Set<UUID>> parties) { JsonObject root=new JsonObject(); parties.forEach((id,members)->{JsonArray a=new JsonArray();members.forEach(member->a.add(member.toString()));root.add(id.toString(),a);}); net.vg.codex.advancement.AtomicJson.write(file(server), root); }
}
