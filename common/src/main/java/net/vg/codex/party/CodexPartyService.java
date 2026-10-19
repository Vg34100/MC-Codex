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
        synchronizeParty(server, parties.get(party));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: joined party with " + inviter.getScoreboardName() + "."));
        inviter.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: " + player.getScoreboardName() + " joined the party."));
    }

    public static void leave(ServerPlayer player) {
        MinecraftServer server = player.level().getServer(); Map<UUID, Set<UUID>> parties = read(server); UUID party = partyOf(parties, player.getUUID());
        if (party == null) { player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: you are not in a party.")); return; }
        parties.get(party).remove(player.getUUID()); if (parties.get(party).isEmpty()) parties.remove(party); write(server, parties);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: left the party."));
    }

    public static void onAdvancementAward(ServerPlayer source, AdvancementHolder advancement) {
        if (!PROPAGATING.add(source.getUUID())) return;
        try {
            Map<UUID, Set<UUID>> parties = read(source.level().getServer()); UUID party = partyOf(parties, source.getUUID()); if (party == null) return;
            for (UUID member : parties.get(party)) {
                if (member.equals(source.getUUID())) continue;
                ServerPlayer teammate = source.level().getServer().getPlayerList().getPlayer(member);
                if (teammate == null) continue;
                for (String criterion : advancement.value().criteria().keySet()) teammate.getAdvancements().award(advancement, criterion);
                teammate.sendSystemMessage(net.minecraft.network.chat.Component.literal("Codex: " + source.getScoreboardName() + " completed " + advancement.id() + " for your party."));
            }
            // The event is emitted when an advancement is complete; synchronize every completed
            // criterion as well, so multi-criterion quests don't lose partial team progress.
            synchronizeParty(source.level().getServer(), parties.get(party));
        } finally { PROPAGATING.remove(source.getUUID()); }
    }

    /** Reconcile an existing party whenever one of its members joins. This covers progress earned
     * while teammates were offline, without requiring a new invite or completion event. */
    public static void onJoin(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        UUID party = partyOf(read(server), player.getUUID());
        if (party != null) synchronizeParty(server, read(server).get(party));
    }

    /** A party UUID is used as the shared submission-progress owner; solo players use themselves. */
    public static UUID progressOwner(MinecraftServer server, UUID playerId) {
        UUID party = partyOf(read(server), playerId);
        return party != null ? party : playerId;
    }

    private static void mergeSubmissionProgress(MinecraftServer server, UUID party, UUID personal) {
        Path pack = AdvancementPackSync.externalPackDir();
        Map<String, Map<Identifier, Integer>> shared = CodexQuestIO.readSubmissionProgress(pack, party);
        Map<String, Map<Identifier, Integer>> individual = CodexQuestIO.readSubmissionProgress(pack, personal);
        for (var task : individual.entrySet()) {
            Map<Identifier, Integer> target = shared.computeIfAbsent(task.getKey(), ignored -> new HashMap<>());
            task.getValue().forEach((item, amount) -> target.merge(item, amount, Integer::sum));
        }
        CodexQuestIO.writeSubmissionProgress(pack, party, shared);
    }

    private static void synchronizeParty(MinecraftServer server, Set<UUID> members) {
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            for (String criterion : holder.value().criteria().keySet()) {
                boolean earnedBySomeone = false;
                for (UUID id : members) {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null && player.getAdvancements().getOrStartProgress(holder).getCriterion(criterion).isDone()) {
                        earnedBySomeone = true;
                        break;
                    }
                }
                if (!earnedBySomeone) continue;
                for (UUID id : members) {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null) player.getAdvancements().award(holder, criterion);
                }
            }
        }
    }

    private static UUID partyOf(Map<UUID, Set<UUID>> parties, UUID player) { for (var e : parties.entrySet()) if (e.getValue().contains(player)) return e.getKey(); return null; }
    private static Path file(MinecraftServer server) { return AdvancementPackSync.externalPackDir().resolve("codex/parties.json"); }
    private static Map<UUID, Set<UUID>> read(MinecraftServer server) { Map<UUID, Set<UUID>> out = new HashMap<>(); Path file=file(server); if(Files.notExists(file)) return out; try { JsonObject root=JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject(); for(var e:root.entrySet()){Set<UUID> members=new HashSet<>();for(var id:e.getValue().getAsJsonArray())members.add(UUID.fromString(id.getAsString()));out.put(UUID.fromString(e.getKey()),members);} }catch(IOException|RuntimeException ignored){} return out; }
    private static void write(MinecraftServer server, Map<UUID, Set<UUID>> parties) { JsonObject root=new JsonObject(); parties.forEach((id,members)->{JsonArray a=new JsonArray();members.forEach(member->a.add(member.toString()));root.add(id.toString(),a);}); try{Files.createDirectories(file(server).getParent());Files.writeString(file(server),GSON.toJson(root),StandardCharsets.UTF_8);}catch(IOException e){throw new IllegalStateException(e);} }
}
