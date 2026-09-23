package com.neuromuser.randomrespawn;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class ConfigNetworking {
    public record ProgressPayload(String key, int progress) implements CustomPayload {
        public static final CustomPayload.Id<ProgressPayload> ID =
                new CustomPayload.Id<>(Identifier.of("random-respawn", "progress"));

        public static final PacketCodec<RegistryByteBuf, ProgressPayload> CODEC =
                PacketCodec.of(
                        (value, buf) -> {
                            buf.writeString(value.key);
                            buf.writeInt(value.progress);
                        },
                        buf -> new ProgressPayload(buf.readString(), buf.readInt())
                );

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void init() {
        PayloadTypeRegistry.playS2C().register(ProgressPayload.ID, ProgressPayload.CODEC);
    }

    public static void sendProgress(ServerPlayerEntity player, String key, int progress) {
        ServerPlayNetworking.send(player, new ProgressPayload(key, progress));
    }
}
