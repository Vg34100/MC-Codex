package net.vg.codex.advancement;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** A single server-thread read operation, never a timed or cross-world persistent cache. */
public final class QuestReadScope implements AutoCloseable {
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private static final class Context { final Map<Path,JsonObject> files=new HashMap<>(); final Map<Object,Object> computed=new HashMap<>(); int reads; }
    private final boolean owner;private final Context context;
    private QuestReadScope(){owner=CURRENT.get()==null;if(owner)CURRENT.set(new Context());context=CURRENT.get();}
    public static QuestReadScope open(){return new QuestReadScope();}
    /** Treat the returned document as read-only; copy the individual state before changing it. */
    public static JsonObject read(Path path)throws IOException{
        Path key=path.toAbsolutePath().normalize();Context scope=CURRENT.get();if(scope!=null&&scope.files.containsKey(key))return scope.files.get(key);
        JsonObject value=Files.exists(key)?JsonParser.parseString(Files.readString(key)).getAsJsonObject():new JsonObject();if(scope!=null){scope.files.put(key,value);scope.reads++;}return value;
    }
    public static void invalidate(Path path){Context scope=CURRENT.get();if(scope!=null){scope.files.remove(path.toAbsolutePath().normalize());scope.computed.clear();}}
    @SuppressWarnings("unchecked")
    public static <T> T memo(Object key, java.util.function.Supplier<T> compute) {
        Context scope=CURRENT.get(); if(scope==null)return compute.get();
        if(scope.computed.containsKey(key))return (T)scope.computed.get(key);
        T value=compute.get(); scope.computed.put(key,value); return value;
    }
    public int reads(){return context.reads;}
    @Override public void close(){if(owner)CURRENT.remove();}
}
