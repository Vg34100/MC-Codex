package net.vg.codex.advancement;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.criterion.ItemPredicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Validate before mutation; allocate overlapping predicates without counting a stack twice. */
public final class SubmissionPlan {
    private SubmissionPlan() { }
    public record Requirement(Identifier key, Identifier icon, String label, int count, ItemPredicate predicate) { }
    public record Allocation(Map<Identifier,Integer> added, Map<Integer,Integer> removals, Map<Identifier,Integer> consumed, int total) { }
    public static Map<Identifier, Integer> requirements(JsonObject task) {
        Map<Identifier,Integer> result = new LinkedHashMap<>();
        for (JsonElement value : items(task)) {
            JsonObject row = value.getAsJsonObject(); validateRow(row);
            result.merge(key(row), row.get("count").getAsBigDecimal().intValueExact(), Math::addExact);
        }
        return result;
    }
    public static JsonArray items(JsonObject task) {
        if (!task.has("items") || !task.get("items").isJsonArray() || task.getAsJsonArray("items").isEmpty() || task.getAsJsonArray("items").size() > 64)
            throw new IllegalArgumentException("A submission needs 1–64 items");
        return task.getAsJsonArray("items");
    }
    private static void validateRow(JsonObject row) {
        if (!row.keySet().stream().allMatch(k -> Set.of("id", "tag", "count", "components").contains(k)) || row.has("id") == row.has("tag"))
            throw new IllegalArgumentException("Delivery needs one item or tag, quantity, and optional exact components");
        Identifier id = Identifier.parse(row.get(row.has("id") ? "id" : "tag").getAsString());
        int count = row.get("count").getAsBigDecimal().intValueExact();
        if (id.toString().length() > 256 || count < 1 || count > 1_000_000 || row.has("id") && (!BuiltInRegistries.ITEM.containsKey(id) || BuiltInRegistries.ITEM.getValue(id) == Items.AIR))
            throw new IllegalArgumentException("Invalid delivery item or quantity: " + id);
        if (row.has("components") && (!row.get("components").isJsonObject() || row.get("components").toString().length() > 8192)) throw new IllegalArgumentException("Invalid delivery components");
    }
    public static Identifier key(JsonObject row) {
        if (row.has("id") && (!row.has("components") || row.getAsJsonObject("components").isEmpty())) return Identifier.parse(row.get("id").getAsString());
        JsonObject identity = row.deepCopy(); identity.remove("count");
        try { return Identifier.fromNamespaceAndPath("codex_internal", "delivery/" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(identity).getBytes(StandardCharsets.UTF_8)))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String canonical(JsonElement value) {
        if (!value.isJsonObject()) return value.toString();
        List<String> keys = new ArrayList<>(value.getAsJsonObject().keySet()); Collections.sort(keys);
        StringBuilder out = new StringBuilder("{"); for (String key : keys) out.append(new JsonPrimitive(key)).append(':').append(canonical(value.getAsJsonObject().get(key))).append(','); return out.append('}').toString();
    }
    public static List<Requirement> resolve(JsonObject task, HolderLookup.Provider registries) {
        Map<Identifier,Integer> counts = requirements(task); Map<Identifier,Requirement> rows = new LinkedHashMap<>();
        for (JsonElement value : items(task)) {
            JsonObject row = value.getAsJsonObject(), predicate = new JsonObject(); Identifier key = key(row);
            Identifier id = Identifier.parse(row.get(row.has("id") ? "id" : "tag").getAsString());
            predicate.addProperty("items", row.has("tag") ? "#" + id : id.toString());
            if (row.has("components")) predicate.add("components", ItemFormats.nativeComponents(row.getAsJsonObject("components")));
            ItemPredicate test = ItemPredicate.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), predicate).getOrThrow();
            Identifier icon = row.has("id") ? id : BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, id)).flatMap(set -> set.stream().findFirst()).map(h -> BuiltInRegistries.ITEM.getKey(h.value())).orElse(BuiltInRegistries.ITEM.getKey(Items.PAPER));
            String label = row.has("tag") ? "Any " + id.getPath().replace('_', ' ') : new ItemStack(BuiltInRegistries.ITEM.getValue(icon)).getHoverName().getString();
            if (row.has("components") && !row.getAsJsonObject("components").isEmpty()) label += " (matching properties)";
            rows.put(key, new Requirement(key, icon, label, counts.get(key), test));
        }
        return List.copyOf(rows.values());
    }
    private static final class Edge { final int to, reverse, initial; int capacity; Edge(int to, int reverse, int capacity) { this.to=to; this.reverse=reverse; this.initial=capacity; this.capacity=capacity; } }
    private static Edge edge(List<List<Edge>> graph, int from, int to, int amount) {
        Edge forward = new Edge(to, graph.get(to).size(), amount), reverse = new Edge(from, graph.get(from).size(), 0); graph.get(from).add(forward); graph.get(to).add(reverse); return forward;
    }
    /** Integral max flow maximizes partial delivery even when tags and exact items overlap. */
    public static Allocation allocate(Inventory inventory, List<Requirement> rows, Map<Identifier,Integer> previous, Optional<Identifier> selected) {
        int slots=inventory.getContainerSize(), sink=1+rows.size()+slots; List<List<Edge>> graph=new ArrayList<>(); for(int i=0;i<=sink;i++) graph.add(new ArrayList<>());
        List<Edge> demands=new ArrayList<>(), capacities=new ArrayList<>();
        for(int r=0;r<rows.size();r++) { Requirement row=rows.get(r); int need=Math.max(0,row.count()-previous.getOrDefault(row.key(),0)); if(selected.isPresent()&&!selected.get().equals(row.key())) need=0;
            demands.add(edge(graph,0,1+r,need));
            for(int s=0;s<slots;s++) { ItemStack stack=inventory.getItem(s); if(!stack.isEmpty()&&row.predicate().test(stack)) edge(graph,1+r,1+rows.size()+s,stack.getCount()); }
        }
        for(int s=0;s<slots;s++) capacities.add(edge(graph,1+rows.size()+s,sink,inventory.getItem(s).getCount()));
        int total=0;
        while(true) { int[] parent=new int[sink+1], branch=new int[sink+1]; Arrays.fill(parent,-1); parent[0]=0; ArrayDeque<Integer> queue=new ArrayDeque<>(); queue.add(0);
            while(!queue.isEmpty()&&parent[sink]<0) { int u=queue.remove(); for(int i=0;i<graph.get(u).size();i++) { Edge e=graph.get(u).get(i); if(e.capacity>0&&parent[e.to]<0) { parent[e.to]=u; branch[e.to]=i; queue.add(e.to); } } }
            if(parent[sink]<0) break; int amount=Integer.MAX_VALUE; for(int v=sink;v!=0;v=parent[v]) amount=Math.min(amount,graph.get(parent[v]).get(branch[v]).capacity);
            for(int v=sink;v!=0;v=parent[v]) { Edge e=graph.get(parent[v]).get(branch[v]); e.capacity-=amount; graph.get(v).get(e.reverse).capacity+=amount; } total+=amount;
        }
        Map<Identifier,Integer> added=new LinkedHashMap<>(), consumed=new LinkedHashMap<>(); Map<Integer,Integer> removals=new LinkedHashMap<>();
        for(int r=0;r<rows.size();r++) { Edge e=demands.get(r); if(e.initial>e.capacity) added.put(rows.get(r).key(),e.initial-e.capacity); }
        for(int s=0;s<slots;s++) { Edge e=capacities.get(s); int amount=e.initial-e.capacity; if(amount>0) { removals.put(s,amount); consumed.merge(BuiltInRegistries.ITEM.getKey(inventory.getItem(s).getItem()),amount,Integer::sum); } }
        return new Allocation(added,removals,consumed,total);
    }
}
