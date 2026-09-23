package com.neuromuser.randomrespawn;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class ConfigNetworking {
    public static final Identifier PROGRESS_ID = new Identifier("random-respawn", "progress");

    public static void sendProgress(ServerPlayerEntity player, String key, int progress) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(key);
        buf.writeInt(progress);
        ServerPlayNetworking.send(player, PROGRESS_ID, buf);
    }
}
