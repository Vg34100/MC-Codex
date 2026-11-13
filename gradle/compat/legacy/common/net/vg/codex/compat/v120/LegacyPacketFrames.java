//? if <1.21 {
/*package net.vg.codex.compat.v120;

import dev.architectury.networking.NetworkManager.Side;
import dev.architectury.networking.transformers.SplitPacketTransformer;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

// Architectury 9 split-packet wire format with bounded, owned receive storage.
public final class LegacyPacketFrames {
    public static final int MAX_MESSAGE = 32 * 1024 * 1024;
    private static final int MAX_PENDING = 64 * 1024 * 1024;
    private static final long TIMEOUT = 30_000_000_000L;
    private static final SplitPacketTransformer OUTBOUND = new SplitPacketTransformer();
    private LegacyPacketFrames() { }

    private static int chunkSize(Side side, ResourceLocation id) {
        return (side == Side.C2S ? 32767 : 1048576) - 25 - id.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    // Input remains caller-owned; emitted frames are borrowed only for the callback.
    public static void encode(Side side, ResourceLocation id, FriendlyByteBuf input, Consumer<FriendlyByteBuf> sink) {
        if (input.readableBytes() > MAX_MESSAGE) throw new IllegalArgumentException("Codex message exceeds 32 MiB");
        FriendlyByteBuf working = new FriendlyByteBuf(input.copy());
        try {
            OUTBOUND.outbound(side, id, working, (direction, channel, frame) -> {
                try { sink.accept(frame); } finally { frame.release(); }
            });
        } finally {
            // Architectury 9 retains source slices while copying multipart frames.
            // This private copy has no references outside this invocation.
            if (working.refCnt() > 0) working.release(working.refCnt());
        }
    }

    public static final class Assembler {
        private record Key(Side side, UUID peer) { }
        private static final class Pending {
            final ResourceLocation id;
            final int expected;
            final long started;
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int received;
            Pending(ResourceLocation id, int expected, long now) { this.id=id; this.expected=expected; this.started=now; }
        }
        private final Map<Key, Pending> pending = new HashMap<>();
        private int pendingBytes;

        public synchronized void clear(UUID peer) {
            var iterator=pending.entrySet().iterator();
            while(iterator.hasNext()) { var entry=iterator.next(); if(Objects.equals(entry.getKey().peer(),peer)) {pendingBytes-=entry.getValue().bytes.size();iterator.remove();} }
        }
        public synchronized void clear() {pending.clear();pendingBytes=0;}
        public synchronized int pendingBytes() {return pendingBytes;}

        // Returns a newly owned complete buffer, or null until all parts arrive.
        public synchronized FriendlyByteBuf accept(Side side, UUID peer, ResourceLocation id, FriendlyByteBuf frame) {
            long now=System.nanoTime();
            var iterator=pending.entrySet().iterator();
            while(iterator.hasNext()) {var value=iterator.next().getValue();if(now-value.started>TIMEOUT){pendingBytes-=value.bytes.size();iterator.remove();}}
            Key key=new Key(side,peer);
            try {
                if(!frame.isReadable())throw new DecoderException("Missing split packet header");
                int kind=frame.readUnsignedByte(), chunk=chunkSize(side,id);
                if(kind==3) {
                    if(pending.containsKey(key))throw new DecoderException("Interleaved split packet");
                    if(frame.readableBytes()>chunk+4)throw new DecoderException("Oversized single packet");
                    return new FriendlyByteBuf(frame.copy());
                }
                Pending value;
                if(kind==0) {
                    if(pending.containsKey(key) || frame.readableBytes()<4)throw new DecoderException("Invalid split packet start");
                    int expected=frame.readInt();
                    if(expected<2 || ((long)expected-1)*chunk+1>MAX_MESSAGE)throw new DecoderException("Invalid split packet count");
                    value=new Pending(id,expected,now);
                    pending.put(key,value);
                } else {
                    value=pending.get(key);
                    if((kind!=1 && kind!=2) || value==null || !value.id.equals(id))throw new DecoderException("Unexpected split packet part");
                }
                int length=frame.readableBytes();
                boolean last=kind==2;
                if(length<1 || length>chunk || (!last && length!=chunk) ||
                        (last ? value.received!=value.expected-1 : value.received>=value.expected-1) ||
                        value.bytes.size()+length>MAX_MESSAGE || pendingBytes+length>MAX_PENDING)
                    throw new DecoderException("Invalid split packet size or order");
                byte[] part=new byte[length];frame.readBytes(part);value.bytes.write(part,0,length);value.received++;pendingBytes+=length;
                if(!last)return null;
                pending.remove(key);pendingBytes-=value.bytes.size();
                return new FriendlyByteBuf(Unpooled.wrappedBuffer(value.bytes.toByteArray()));
            } catch(RuntimeException error) {
                Pending abandoned=pending.remove(key);if(abandoned!=null)pendingBytes-=abandoned.bytes.size();
                throw error;
            }
        }
    }
}
*///?}
