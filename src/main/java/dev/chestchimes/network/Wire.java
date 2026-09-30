package dev.chestchimes.network;

import dev.chestchimes.audio.AudioRules;
import dev.chestchimes.server.ChestService;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Wire {
    public static final int BEGIN = 0, CHUNK = 1, RESET = 2, ADJUST = 3, MIGRATED = 4;
    public static final int STATE = 10, REPLY = 11, PLAY_BEGIN = 12, PLAY_CHUNK = 13, STOP = 14, STATE_CHUNK = 15, MIGRATE = 16, MIGRATE_CHUNK = 17, CLOSE = 18;
    private static final UUID ZERO = new UUID(0, 0);
    public static Consumer<Message> clientReceiver = message -> {};
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("chestchimes", "audio"), () -> "2", "2"::equals, "2"::equals);

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
    public static void init() {
        CHANNEL.messageBuilder(Message.class, 0)
                .encoder(Message::encode).decoder(Message::decode)
                .consumerMainThread((message, supplier) -> {
                    var context = supplier.get();
                    if (context.getDirection() == NetworkDirection.PLAY_TO_SERVER) {
                        ServerPlayer sender = context.getSender();
                        if (sender != null && message.type >= BEGIN && message.type <= MIGRATED)
                            ChestService.receive(sender, message);
                    } else if (context.getDirection() == NetworkDirection.PLAY_TO_CLIENT && message.type >= STATE) {
                        clientReceiver.accept(message);
                    }
                    context.setPacketHandled(true);
                }).add();
    }
    public static void toServer(Message message) { CHANNEL.sendToServer(message); }
    public static void toPlayer(ServerPlayer player, Message message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
