//? if <1.21 {
/*package net.vg.codex.validation;

import net.vg.codex.compat.v120.LegacyPacketFrames;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.function.BiConsumer;
import dev.architectury.networking.NetworkManager.Side;

final class PacketFragmentScenarios {
    static void run(BiConsumer<String,Boolean> check) {
        ResourceLocation id=new ResourceLocation("codex","fragment_test");UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
        for(Side side:List.of(Side.C2S,Side.S2C)) {
            for(int size:new int[]{0,128,side==Side.C2S?100_000:2_100_000}) {
                byte[] expected=new byte[size];new Random(7).nextBytes(expected);
                FriendlyByteBuf input=new FriendlyByteBuf(Unpooled.wrappedBuffer(expected));
                var assembler=new LegacyPacketFrames.Assembler();List<FriendlyByteBuf> frames=new ArrayList<>();
                LegacyPacketFrames.encode(side,id,input,frame->frames.add(new FriendlyByteBuf(frame.copy())));
                boolean complete=false;
                for(var frame:frames)try{
                    var result=assembler.accept(side,alice,id,frame);
                    if(result!=null)try{byte[] actual=new byte[result.readableBytes()];result.readBytes(actual);complete=Arrays.equals(expected,actual);}finally{result.release();}
                }finally{frame.release();}
                check.accept("Legacy "+side+" packet round trip "+size+" bytes",complete&&assembler.pendingBytes()==0&&input.refCnt()==1&&input.readerIndex()==0);input.release();
            }
        }
        var assembler=new LegacyPacketFrames.Assembler();
        for(int header:new int[]{0,1,2,4}) {
            var frame=new FriendlyByteBuf(Unpooled.buffer());frame.writeByte(header);if(header==0)frame.writeInt(Integer.MAX_VALUE);
            boolean rejected=false;try{assembler.accept(Side.C2S,alice,id,frame);}catch(RuntimeException expected){rejected=true;}finally{frame.release();}
            check.accept("Malformed legacy split header "+header+" rejected",rejected&&assembler.pendingBytes()==0);
        }
        var input=new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[100_000]));List<FriendlyByteBuf> frames=new ArrayList<>();
        LegacyPacketFrames.encode(Side.C2S,id,input,frame->frames.add(new FriendlyByteBuf(frame.copy())));input.release();
        try {
            assembler.accept(Side.C2S,alice,id,new FriendlyByteBuf(frames.get(0).duplicate()));
            boolean rejected=false;try{assembler.accept(Side.C2S,bob,id,new FriendlyByteBuf(frames.get(1).duplicate()));}catch(RuntimeException expected){rejected=true;}
            check.accept("Legacy fragment chains are isolated per player",rejected&&assembler.pendingBytes()>0);
            rejected=false;try{assembler.accept(Side.C2S,alice,new ResourceLocation("codex","wrong"),new FriendlyByteBuf(frames.get(1).duplicate()));}catch(RuntimeException expected){rejected=true;}
            check.accept("Wrong-channel fragment clears only its pending chain",rejected&&assembler.pendingBytes()==0);
            assembler.accept(Side.C2S,alice,id,new FriendlyByteBuf(frames.get(0).duplicate()));assembler.clear(alice);
            check.accept("Disconnect discards pending legacy fragments",assembler.pendingBytes()==0);
        }finally{frames.forEach(FriendlyByteBuf::release);assembler.clear();}
    }
}
*///?}
