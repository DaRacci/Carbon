/*
 * CarbonChat
 *
 * Copyright (c) 2021 Josua Parks (Vicarious)
 *                    Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.draycia.carbon.fabric;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.draycia.carbon.api.CarbonChat;
import net.draycia.carbon.api.CarbonChatProvider;
import net.draycia.carbon.api.CarbonServer;
import net.draycia.carbon.api.channels.ChannelRegistry;
import net.draycia.carbon.api.events.CarbonEventHandler;
import net.draycia.carbon.api.users.UserManager;
import net.draycia.carbon.api.util.RenderedMessage;
import net.draycia.carbon.common.channels.CarbonChannelRegistry;
import net.draycia.carbon.common.messages.CarbonMessages;
import net.draycia.carbon.common.messaging.MessagingManager;
import net.draycia.carbon.common.users.CarbonPlayerCommon;
import net.draycia.carbon.common.util.CloudUtils;
import net.draycia.carbon.common.util.ListenerUtils;
import net.draycia.carbon.common.util.PlayerUtils;
import net.draycia.carbon.fabric.callback.ChatCallback;
import net.draycia.carbon.fabric.command.DeleteMessageCommand;
import net.draycia.carbon.fabric.listeners.FabricChatListener;
import net.draycia.carbon.fabric.listeners.FabricChatPreviewListener;
import net.draycia.carbon.fabric.listeners.FabricPlayerJoinListener;
import net.draycia.carbon.fabric.listeners.FabricPlayerLeaveListener;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.util.TriState;
import net.kyori.moonshine.message.IMessageRenderer;
import net.luckperms.api.LuckPermsProvider;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import ninja.egg82.messenger.services.PacketService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;

import static java.util.Objects.requireNonNull;

@DefaultQualifier(NonNull.class)
public final class CarbonChatFabric implements ModInitializer, CarbonChat {

    private final CarbonEventHandler eventHandler = new CarbonEventHandler();
    private @Nullable MinecraftServer minecraftServer;
    private @MonotonicNonNull ModContainer modContainer;
    private @MonotonicNonNull Injector injector;
    private @MonotonicNonNull UserManager<CarbonPlayerCommon> userManager;
    private @MonotonicNonNull Logger logger;
    private @MonotonicNonNull CarbonServerFabric carbonServerFabric;
    private @MonotonicNonNull CarbonMessages carbonMessages;
    private @MonotonicNonNull ChannelRegistry channelRegistry;
    private TriState luckPermsLoaded = TriState.NOT_SET;
    private final UUID serverId = UUID.randomUUID();

    public static ResourceKey<ChatType> CHAT_TYPE = ResourceKey.create(Registry.CHAT_TYPE_REGISTRY, new ResourceLocation("carbon", "chat"));

    private static final Cache<UUID, MessageSignature> messageSignatures = CacheBuilder.newBuilder()
        .maximumSize(10)
        .build();

    private @MonotonicNonNull MessagingManager messagingManager = null;

    @Override
    public void onInitialize() {
        this.modContainer = FabricLoader.getInstance().getModContainer("carbonchat").orElseThrow(() ->
            new IllegalStateException("Could not find ModContainer for carbonchat."));

        CarbonChatProvider.register(this);

        this.logger = LogManager.getLogger(this.modContainer.getMetadata().getName());
        this.injector = Guice.createInjector(new CarbonChatFabricModule(this, this.logger, this.dataDirectory()));
        this.carbonMessages = this.injector.getInstance(CarbonMessages.class);
        this.channelRegistry = this.injector.getInstance(ChannelRegistry.class);
        this.carbonServerFabric = this.injector.getInstance(CarbonServerFabric.class);
        this.userManager = this.injector.getInstance(com.google.inject.Key.get(new TypeLiteral<UserManager<CarbonPlayerCommon>>() {}));

        // Platform Listeners
        this.registerChatListener();
        this.registerServerLifecycleListeners();
        this.registerPlayerStatusListeners();
        this.registerTickListeners();

        // Listeners
        ListenerUtils.registerCommonListeners(this.injector);

        // Commands
        CloudUtils.loadCommands(this.injector);
        final var commandSettings = CloudUtils.loadCommandSettings(this.injector);
        CloudUtils.registerCommands(commandSettings);
        this.injector.getInstance(DeleteMessageCommand.class).init();

        // Load channels
        ((CarbonChannelRegistry) this.channelRegistry()).loadConfigChannels(this.carbonMessages);
    }

    public static void addMessageSignature(final UUID uuid, final MessageSignature messageSignature) {
        messageSignatures.put(uuid, messageSignature);
    }

    public static @Nullable MessageSignature messageSignature(final UUID uuid) {
        return messageSignatures.getIfPresent(uuid);
    }

    public static Set<UUID> messageIds() {
        return messageSignatures.asMap().keySet();
    }

    public static List<String> messageIdSuggestions() {
        final List<String> suggestions = new ArrayList<>();

        for (final UUID messageId : messageSignatures.asMap().keySet()) {
            suggestions.add(messageId.toString());
        }

        return suggestions;
    }

    private void registerChatListener() {
        ChatCallback.setup();
        ChatCallback.INSTANCE.registerListener(new FabricChatListener(this, this.channelRegistry));
        ServerMessageDecoratorEvent.EVENT.register(ServerMessageDecoratorEvent.CONTENT_PHASE, this.injector.getInstance(FabricChatPreviewListener.class));
    }

    private void registerServerLifecycleListeners() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> this.minecraftServer = server);
        ServerLifecycleEvents.SERVER_STOPPING.register($ -> PlayerUtils.saveLoggedInPlayers(this.carbonServerFabric, this.userManager).forEach(CompletableFuture::join));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> this.minecraftServer = null);
    }

    private void registerPlayerStatusListeners() {
        ServerPlayConnectionEvents.DISCONNECT.register(this.injector.getInstance(FabricPlayerLeaveListener.class));
        ServerPlayConnectionEvents.JOIN.register(this.injector.getInstance(FabricPlayerJoinListener.class));
    }

    private void registerTickListeners() {
        final long saveDelay = 5 * 60 * 20; // 5 minutes

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() != 0 && server.getTickCount() % saveDelay == 0) {
                PlayerUtils.saveLoggedInPlayers(this.carbonServerFabric, this.userManager);
            }
        });
    }

    @Override
    public UUID serverId() {
        return this.serverId;
    }

    @Override
    public @Nullable PacketService packetService() {
        if (this.messagingManager == null) {
            this.messagingManager = this.injector.getInstance(MessagingManager.class);
        }

        return this.messagingManager.packetService();
    }

    @Override
    public Logger logger() {
        return this.logger;
    }

    @Override
    public Path dataDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve(this.modContainer.getMetadata().getId());
    }

    @Override
    public CarbonEventHandler eventHandler() {
        return this.eventHandler;
    }

    @Override
    public CarbonServer server() {
        return this.carbonServerFabric;
    }

    @Override
    public ChannelRegistry channelRegistry() {
        return this.channelRegistry;
    }

    @Override
    public <T extends Audience> IMessageRenderer<T, String, RenderedMessage, Component> messageRenderer() {
        return this.injector.getInstance(FabricMessageRenderer.class);
    }

    public MinecraftServer minecraftServer() {
        return requireNonNull(this.minecraftServer, "Attempted to get the MinecraftServer instance when one is not active.");
    }

    public CarbonMessages carbonMessages() {
        return this.carbonMessages;
    }

    public boolean luckPermsLoaded() {
        if (this.luckPermsLoaded == TriState.NOT_SET) {
            try {
                LuckPermsProvider.get();
                this.luckPermsLoaded = TriState.TRUE;
            } catch (final NoClassDefFoundError exception) {
                this.luckPermsLoaded = TriState.FALSE;
            }
        }

        return this.luckPermsLoaded == TriState.TRUE;
    }

}
