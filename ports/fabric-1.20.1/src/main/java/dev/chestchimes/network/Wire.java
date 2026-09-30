package dev.chestchimes.network;

import dev.chestchimes.audio.AudioRules;
import dev.chestchimes.server.ChestService;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class Wire {
    public static final int BEGIN = 0, CHUNK = 1, RESET = 2, ADJUST = 3, MIGRATED = 4;
    public static final int STATE = 10, REPLY = 11, PLAY_BEGIN = 12, PLAY_CHUNK = 13, STOP = 14, STATE_CHUNK = 15, MIGRATE = 16, MIGRATE_CHUNK = 17, CLOSE = 18;
    private static final UUID ZERO = new UUID(0, 0);
    public static Consumer<Message> clientReceiver = message -> {};

    public record Message(int type, int menu, UUID transfer, BlockPos pos, boolean shared,
                          String name, int total, int index, byte[] bytes, String key, int volume) {
        public Message(int type, int menu, UUID transfer, BlockPos pos, boolean shared,
                       String name, int total, int index, byte[] bytes) {
            this(type, menu, transfer, pos, shared, name, total, index, bytes, "", 100);
        }
        public static Message simple(int type, int menu) {
            return new Message(type, menu, ZERO, BlockPos.ZERO, false, "", 0, 0, new byte[0]);
        }
        private void encode(FriendlyByteBuf b) {
            b.writeVarInt(type); b.writeVarInt(menu); b.writeUUID(transfer); b.writeBlockPos(pos);
            b.writeBoolean(shared); b.writeUtf(name, 160); b.writeVarInt(total); b.writeVarInt(index);
            b.writeByteArray(bytes); b.writeUtf(key, 160); b.writeVarInt(volume);
        }
        public static Message decode(FriendlyByteBuf b) {
            return new Message(b.readVarInt(), b.readVarInt(), b.readUUID(), b.readBlockPos(),
                    b.readBoolean(), b.readUtf(160), b.readVarInt(), b.readVarInt(),
                    b.readByteArray(AudioRules.CHUNK), b.readUtf(160), b.readVarInt());
        }
    }
    private static final ResourceLocation CHANNEL=new ResourceLocation("chestchimes","audio");
    public static void init() {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(CHANNEL,(server,player,handler,buf,sender)->{
            Message message=Message.decode(buf);
            server.execute(()->{ if(message.type()>=BEGIN && message.type()<=MIGRATED) ChestService.receive(player,message); });
        });
    }
    @net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
    public static void initClient() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(CHANNEL,(mc,handler,buf,sender)->{
            Message message=Message.decode(buf);
            mc.execute(()->{ if(message.type()>=STATE) dev.chestchimes.client.ClientState.accept(message); });
        });
    }
    @net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
    public static void toServer(Message message) {
        var buf=net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create(); message.encode(buf);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(CHANNEL,buf);
    }
    public static void toPlayer(ServerPlayer player,Message message) {
        if(!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player,CHANNEL)) return;
        var buf=net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create(); message.encode(buf);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,CHANNEL,buf);
    }
}
