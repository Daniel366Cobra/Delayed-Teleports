package dev.itsmeow.delayedteleports;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.itsmeow.delayedteleports.util.FTC;
import dev.itsmeow.delayedteleports.util.HereTeleport;
import dev.itsmeow.delayedteleports.util.Teleport;
import dev.itsmeow.delayedteleports.util.ToTeleport;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.arguments.GameProfileArgument;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.function.Predicate;


@Mod(DelayedTeleportsMod.MOD_ID)
@Mod.EventBusSubscriber(modid = DelayedTeleportsMod.MOD_ID)
public class DelayedTeleportsMod {

    enum CancelationReason {
        TIMEOUT,
        CANCELED
    }

    public static final String MOD_ID = "delayedteleports";

    public static final String CONFIG_FIELD_TIMEOUT_NAME = "teleport_request_timeout";
    public static final String CONFIG_FIELD_DELAY_NAME = "teleport_request_delay";
    public static final String CONFIG_FIELD_TIMEOUT_COMMENT = "Timeout until a teleport request expires, in seconds.";
    public static final String CONFIG_FIELD_DELAY_COMMENT = "Delay before an accepted teleport request activates, in seconds.";
    public static final int CONFIG_FIELD_TIMEOUT_VALUE = 30;
    public static final int CONFIG_FIELD_DELAY_VALUE = 5;
    public static final int CONFIG_FIELD_MIN = 0;
    public static final int CONFIG_FIELD_MAX = Integer.MAX_VALUE;

    public static ServerConfig SERVER_CONFIG = null;
    private static ForgeConfigSpec SERVER_CONFIG_SPEC = null;

    public static HashMap<Teleport, Integer> tpRequestsWithTimeout = new HashMap<>();

    //Maps for saving accepted TPs with delay and requester BlockPos
    public static HashMap<Teleport, BlockPos> acceptedTPsWithRequesterLocation = new HashMap<>();
    public static HashMap<Teleport, Integer> acceptedTPsWithDelay = new HashMap<>();

    public static class ServerConfig {
        public ForgeConfigSpec.Builder builder;
        public final ForgeConfigSpec.IntValue teleportRequestTimeout;
        public final ForgeConfigSpec.IntValue teleportDelayTime;

        ServerConfig(ForgeConfigSpec.Builder builder) {
            this.builder = builder;
            this.teleportRequestTimeout = builder.comment(
                            DelayedTeleportsMod.CONFIG_FIELD_TIMEOUT_COMMENT
                                    + " Place a copy of this config in the defaultconfigs/ folder in the main server/.minecraft directory (or make the folder if it's not there) to copy this to new worlds.")
                    .defineInRange(DelayedTeleportsMod.CONFIG_FIELD_TIMEOUT_NAME,
                            DelayedTeleportsMod.CONFIG_FIELD_TIMEOUT_VALUE,
                            DelayedTeleportsMod.CONFIG_FIELD_MIN,
                            DelayedTeleportsMod.CONFIG_FIELD_MAX);
            this.teleportDelayTime = builder.comment(
                            DelayedTeleportsMod.CONFIG_FIELD_DELAY_COMMENT)
                    .defineInRange(DelayedTeleportsMod.CONFIG_FIELD_DELAY_NAME,
                            DelayedTeleportsMod.CONFIG_FIELD_DELAY_VALUE,
                            DelayedTeleportsMod.CONFIG_FIELD_MIN,
                            DelayedTeleportsMod.CONFIG_FIELD_MAX);
            builder.build();
        }
    }

    public DelayedTeleportsMod() {
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST, () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (s, b) -> true));
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::loadComplete);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher dispatcher = event.getDispatcher();

        Predicate<CommandSource> isPlayer = source -> {
            try {
                return source.getPlayerOrException() != null;
            } catch(CommandSyntaxException e) {
                return false;
            }
        };
        // tpa
        dispatcher.register(Commands.literal("tpa")
                .requires(isPlayer)
                .then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(command -> {
                    ServerPlayerEntity player = command.getSource().getPlayerOrException();
                    MinecraftServer server = player.getServer();
                    Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(command, "target");
                    if(profiles.size() > 1) {
                        sendMessage(command.getSource(), false, new FTC(TextFormatting.RED, "Specify one player as an argument!"));
                        return 0;
                    }
                    GameProfile profile = getFirstProfile(profiles);
                    if(!isGameProfileOnline(server, profile)) {
                        sendMessage(command.getSource(), false, new FTC(TextFormatting.RED, "This player is not online!"));
                        return 0;
                    }
                    if(profile.getId().equals(player.getGameProfile().getId())) {
                        sendMessage(command.getSource(), false, new FTC(TextFormatting.RED, "You cannot teleport to yourself!"));
                        return 0;
                    }
                    String sourceName = player.getName().getString();
                    ServerPlayerEntity targetPlayer = server.getPlayerList().getPlayer(profile.getId());
                    Teleport remove = DelayedTeleportsMod.getRequesterTP(sourceName);
                    if(remove != null) {
                        DelayedTeleportsMod.tpRequestsWithTimeout.remove(remove);
                        DelayedTeleportsMod.notifyCanceledTP(server, remove, CancelationReason.CANCELED);
                    }

                    ToTeleport teleport = new ToTeleport(sourceName, targetPlayer.getName().getString());

                    DelayedTeleportsMod.tpRequestsWithTimeout.put(teleport, getTeleportTimeout() * 20);

                    sendMessage(targetPlayer.createCommandSourceStack(), true, new FTC(TextFormatting.GREEN, sourceName), new FTC(TextFormatting.GOLD, " has requested to teleport to you. Type "), new FTC(TextFormatting.YELLOW, "/tpaccept"), new FTC(TextFormatting.GOLD, " to accept."));
                    sendMessage(command.getSource(), true, new FTC(TextFormatting.GOLD, "Requested to teleport to "), new FTC(TextFormatting.GREEN, targetPlayer.getName().getString()), new FTC(TextFormatting.GOLD, "."));
                    return 1;
                })));

        // tpaccept
        dispatcher.register(Commands.literal("tpaccept").requires(isPlayer).executes(command -> {
            ServerPlayerEntity player = command.getSource().getPlayerOrException();
            MinecraftServer server = player.getServer();

            //a player using the tpaccept command is the SUBJECT,
            //irrelevant of the teleport type,
            //so we obtain the teleport by the Subject name.
            Teleport tp = DelayedTeleportsMod.getSubjectTP(player.getName().getString());

            //if the teleport does not exist
            //it means there are no pending requests.
            if(tp == null) {
                sendMessage(command.getSource(), false, new FTC(TextFormatting.RED, "You have no pending teleport requests!"));
                return 0;
            }

            //Assume a /tpa was accepted. That means the Requester wants to Move to the Subject.
            ServerPlayerEntity playerHosting = server.getPlayerList().getPlayerByName(tp.getSubject());
            ServerPlayerEntity playerMoving = server.getPlayerList().getPlayerByName(tp.getRequester());

            //if it was a /tpahere, then the roles reverse.
            if (tp instanceof HereTeleport) {
                ServerPlayerEntity holder = playerMoving;
                playerMoving = playerHosting;
                playerHosting = holder;
            }

            //this only will show in case of /tpa, because a null player cannot receive the message.
            if(playerMoving == null) {
                sendMessage(command.getSource(), false, new FTC(TextFormatting.RED, "The player that is teleporting no longer exists!"));
                return 0;
            }

            sendMessage(playerHosting.createCommandSourceStack(), true, new FTC(TextFormatting.GREEN, "Teleport request accepted."));
            sendMessage(playerMoving.createCommandSourceStack(), true, new FTC(TextFormatting.GREEN, (tp instanceof ToTeleport ? "Your teleport request has been accepted. Stay still." : "You are now being teleported. Stay still.")));

            //We delete the accepted request...
            DelayedTeleportsMod.tpRequestsWithTimeout.remove(tp);
            //...and put the active TP on a map with the delay.
            acceptedTPsWithDelay.put(tp, DelayedTeleportsMod.getTeleportDelay() * 20);
            //The moving player must stay still for the delay time.
            acceptedTPsWithRequesterLocation.put(tp, playerMoving.blockPosition());

            return 1;
        }));

        // tpahere
        dispatcher.register(Commands.literal("tpahere").requires(isPlayer).then(Commands.argument("target", GameProfileArgument.gameProfile()).executes(command -> {
            ServerPlayerEntity player = command.getSource().getPlayerOrException();
            MinecraftServer server = player.getServer();
            Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(command, "target");
            if(profiles.size() > 1) {
                sendMessage(command.getSource(), false, new FTC(TextFormatting.RED, "Specify one player as an argument!"));
                return 0;
            }
            GameProfile profile = getFirstProfile(profiles);
            if(!isGameProfileOnline(server, profile)) {
                sendMessage(command.getSource(), false, new FTC(TextFormatting.RED, "This player is not online!"));
                return 0;
            }
            if(profile.getId().equals(player.getGameProfile().getId())) {
                sendMessage(command.getSource(), false, new FTC(TextFormatting.RED, "You cannot send a teleport request to yourself!"));
                return 0;
            }
            String sourceName = player.getName().getString();
            Teleport remove = DelayedTeleportsMod.getRequesterTP(sourceName);
            if(remove != null) {
                DelayedTeleportsMod.tpRequestsWithTimeout.remove(remove);
                DelayedTeleportsMod.notifyCanceledTP(server, remove, CancelationReason.CANCELED);
            }
            ServerPlayerEntity targetPlayer = server.getPlayerList().getPlayer(profile.getId());

            HereTeleport tp = new HereTeleport(sourceName, targetPlayer.getName().getString());

            DelayedTeleportsMod.tpRequestsWithTimeout.put(tp, getTeleportTimeout() * 20);

            sendMessage(targetPlayer.createCommandSourceStack(), true, new FTC(TextFormatting.GREEN, sourceName), new FTC(TextFormatting.GOLD, " has requested that you teleport to them. Type "), new FTC(TextFormatting.YELLOW, "/tpaccept"), new FTC(TextFormatting.GOLD, " to accept."));
            sendMessage(command.getSource(), true, new FTC(TextFormatting.GOLD, "Requested "), new FTC(TextFormatting.GREEN, targetPlayer.getName().getString()), new FTC(TextFormatting.GOLD, " to teleport to you."));

            return 1;
        })));
    }

    private void loadComplete(final FMLLoadCompleteEvent event) {
        final Pair<ServerConfig, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        SERVER_CONFIG_SPEC = specPair.getRight();
        SERVER_CONFIG = specPair.getLeft();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_CONFIG_SPEC);
    }

    public static int getTeleportTimeout() {
        return DelayedTeleportsMod.SERVER_CONFIG.teleportRequestTimeout.get();
    }

    public static int getTeleportDelay() {
        return DelayedTeleportsMod.SERVER_CONFIG.teleportDelayTime.get();
    }

    private static boolean isGameProfileOnline(MinecraftServer server, GameProfile profile) {
        ServerPlayerEntity player = server.getPlayerList().getPlayer(profile.getId());
        if(player != null) {
            if(server.getPlayerList().getPlayers().contains(player)) {
                return true;
            }
        }
        return false;
    }

    private static GameProfile getFirstProfile(Collection<GameProfile> profiles) {
        for(GameProfile profile : profiles) {
            return profile;
        }
        return null;
    }


    @Nullable
    public static Teleport getSubjectTP(String name) {
        for(Teleport tp : DelayedTeleportsMod.tpRequestsWithTimeout.keySet()) {
            if(tp.getSubject().equalsIgnoreCase(name)) {
                return tp;
            }
        }
        return null;
    }

    @Nullable
    public static Teleport getRequesterTP(String name) {
        for(Teleport tp : DelayedTeleportsMod.tpRequestsWithTimeout.keySet()) {
            if(tp.getRequester().equalsIgnoreCase(name)) {
                return tp;
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        HashSet<Teleport> toRemove = new HashSet<>();

        //Decrease timeouts, collect expired teleports for removal
        for(Teleport tp : tpRequestsWithTimeout.keySet()) {
            int time = tpRequestsWithTimeout.get(tp);
            if(time > 0) {
                time--;
                tpRequestsWithTimeout.put(tp, time);
            } else {
                toRemove.add(tp);
                notifyCanceledTP(server, tp, CancelationReason.TIMEOUT);
            }
        }
        for(Teleport remove : toRemove) {
            tpRequestsWithTimeout.remove(remove);
        }

        toRemove = new HashSet<>();

        //Check for teleports whose moving player has moved or was damaged since request, collect them for removal
        for(Teleport tp : acceptedTPsWithRequesterLocation.keySet()) {
            //Again, first case is /tpa. Requester moves to subject
            ServerPlayerEntity playerMoving = server.getPlayerList().getPlayerByName(tp.getRequester());
            //Swap players if /tpahere.
            if (tp instanceof HereTeleport)
            {
                playerMoving = server.getPlayerList().getPlayerByName(tp.getSubject());
            }

            BlockPos requesterBlockPos = playerMoving.blockPosition();
            if (!requesterBlockPos.equals(acceptedTPsWithRequesterLocation.get(tp)) || playerMoving.hurtTime != 0) {
                toRemove.add(tp);
                notifyCanceledTP(server, tp, CancelationReason.CANCELED);
            }
        }
        for(Teleport remove : toRemove) {
            acceptedTPsWithRequesterLocation.remove(remove);
            acceptedTPsWithDelay.remove(remove);
        }

        toRemove = new HashSet<>();

        //Check for accepted TPs, reduce delays, execute ready ones
        for(Teleport tp : acceptedTPsWithDelay.keySet()) {
            int cooldown = acceptedTPsWithDelay.get(tp);
            if(cooldown > 0) {
                cooldown--;
                acceptedTPsWithDelay.put(tp, cooldown);
                if (cooldown % 20 == 0) {
                    sendMessage(server.getPlayerList().getPlayerByName(tp instanceof ToTeleport? tp.getRequester() : tp.getSubject()).createCommandSourceStack(),
                            true,
                            new FTC(TextFormatting.GOLD, "You will be teleported in "),
                            new FTC(TextFormatting.YELLOW, String.valueOf(cooldown / 20)),
                            new FTC(TextFormatting.GOLD, " seconds. Do not move."));
                }
            } else {
                toRemove.add(tp);
                executeTeleport(server, tp);
            }
        }
        for(Teleport remove : toRemove) {
            acceptedTPsWithRequesterLocation.remove(remove);
            acceptedTPsWithDelay.remove(remove);
        }
    }

    public static void executeTeleport(MinecraftServer server, Teleport tp) {
        //Again, first case is /tpa. Requester moves to subject
        ServerPlayerEntity playerMoving = server.getPlayerList().getPlayerByName(tp.getRequester());
        ServerPlayerEntity playerHosting = server.getPlayerList().getPlayerByName(tp.getSubject());
        //Swap players if /tpahere.
        if (tp instanceof HereTeleport)
        {
            ServerPlayerEntity holder = playerMoving;
            playerMoving = playerHosting;
            playerHosting = holder;
        }
        double posX = playerHosting.getX();
        double posY = playerHosting.getY();
        double posZ = playerHosting.getZ();
        playerMoving.teleportTo(playerHosting.getLevel(), posX, posY, posZ, playerHosting.yRot, 0F);
    }

    public static void notifyCanceledTP(MinecraftServer server, Teleport tp, CancelationReason reason) {
        ServerPlayerEntity tper = server.getPlayerList().getPlayerByName(tp.getRequester());
        ServerPlayerEntity target = server.getPlayerList().getPlayerByName(tp.getSubject());

        String msgTargetPreface = "Teleport request from ", msgTPerPreface = "Your request to ";
        String msgTargetReason = " was canceled.", msgTPerReason = " was canceled.";

        switch (reason) {
            case TIMEOUT: {
                msgTargetReason = " timed out.";
                msgTPerReason = " has timed out after not being accepted.";
                break;
            }
            case CANCELED: {
                msgTargetReason = " has been canceled.";
                msgTPerReason = " has been canceled.";
                break;
            }
            default:
                break;
        }

        if(target != null) {
            sendMessage(target.createCommandSourceStack(), true, new FTC(TextFormatting.GOLD, msgTargetPreface), new FTC(TextFormatting.GREEN, tp.getRequester()), new FTC(TextFormatting.GOLD, msgTargetReason));
        }
        if(tper != null) {
            sendMessage(tper.createCommandSourceStack(), true, new FTC(TextFormatting.GOLD, msgTPerPreface), new FTC(TextFormatting.GREEN, tp.getSubject()), new FTC(TextFormatting.GOLD, msgTPerReason));
        }
    }

    public static void sendMessage(CommandSource source, boolean success, TextComponent... styled) {
        if(styled.length > 0) {
            TextComponent comp = styled[0];
            if(styled.length > 1) {
                for(int i = 1; i < styled.length; i++) {
                    comp.append(styled[i]);
                }
            }
            if(success) {
                source.sendSuccess(comp, false);
            } else {
                source.sendFailure(comp);
            }
        }
    }

}
