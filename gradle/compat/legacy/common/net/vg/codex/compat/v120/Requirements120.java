//? if <1.21 {
/*package net.vg.codex.compat.v120;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.advancements.*;
import java.util.*;
import java.util.function.Predicate;
public final class Requirements120 {
 public static final Requirements120 EMPTY=new Requirements120(new String[0][]);
 private final String[][] rows;
 public Requirements120(String[][] values){rows=Arrays.stream(values).map(String[]::clone).toArray(String[][]::new);}
 public Requirements120(FriendlyByteBuf buf){int count=bound(buf.readVarInt(),4096);rows=new String[count][];int total=0;for(int i=0;i<count;i++){int n=bound(buf.readVarInt(),4096);total+=n;if(total>65536)throw new io.netty.handler.codec.DecoderException("Too many requirement entries");rows[i]=new String[n];for(int j=0;j<n;j++)rows[i][j]=buf.readUtf(256);}}
 private static int bound(int n,int max){if(n<0||n>max)throw new io.netty.handler.codec.DecoderException("Invalid requirements");return n;}
 public void write(FriendlyByteBuf buf){buf.writeVarInt(rows.length);for(var row:rows){buf.writeVarInt(row.length);for(String name:row)buf.writeUtf(name,256);}}
 public Set<String> names(){Set<String> names=new LinkedHashSet<>();for(var row:rows)Collections.addAll(names,row);return names;}
 public int size(){return rows.length;}
 public boolean test(Predicate<String> completed){if(rows.length==0)return false;for(var row:rows){boolean any=false;for(String key:row)any|=completed.test(key);if(!any)return false;}return true;}
 public void apply(AdvancementProgress progress){Map<String,Criterion> criteria=new HashMap<>();for(var row:rows)for(String name:row)criteria.put(name,new Criterion());progress.update(criteria,rows);}
 public static Requirements120 allOf(Collection<String> names){return new Requirements120(names.stream().map(s->new String[]{s}).toArray(String[][]::new));}
 public static Requirements120 anyOf(Collection<String> names){return new Requirements120(new String[][]{names.toArray(String[]::new)});}
 public String[][] rows(){return Arrays.stream(rows).map(String[]::clone).toArray(String[][]::new);}
}

*///?}
