package de.torui.coflsky.handlers;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.realmsclient.util.Pair;

import de.torui.coflsky.CoflSky;
import de.torui.coflsky.FlipHandler.Flip;
import de.torui.coflsky.WSCommandHandler;
import de.torui.coflsky.commands.Command;
import de.torui.coflsky.commands.CommandType;
import de.torui.coflsky.commands.JsonStringCommand;
import de.torui.coflsky.commands.models.AuctionData;
import de.torui.coflsky.configuration.Configuration;
import de.torui.coflsky.minecraft_integration.CountdownTimer;
import de.torui.coflsky.network.WSClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent.KeyInputEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import static de.torui.coflsky.CoflSky.config;
import static de.torui.coflsky.handlers.DescriptionHandler.*;
import static de.torui.coflsky.handlers.EventHandler.*;

public class EventRegistry {
	public static Pattern chatPattern = Pattern.compile("a^", Pattern.CASE_INSENSITIVE);
	public final ExecutorService chatThreadPool = Executors.newFixedThreadPool(2);
	public final ExecutorService tickThreadPool = Executors.newFixedThreadPool(2);
	@SubscribeEvent
	public void onDisconnectedFromServerEvent(ClientDisconnectionFromServerEvent event) {
		if(CoflSky.Wrapper.isRunning) {
			CoflSky.logger.debug("Disconnected from server");
			CoflSky.Wrapper.stop();
			CoflSky.logger.debug("CoflSky stopped");
		}
	}
	
	public long LastClick = System.currentTimeMillis();
	private DescriptionHandler descriptionHandler;

	@SideOnly(Side.CLIENT)
	@SubscribeEvent(priority = EventPriority.NORMAL, receiveCanceled = true)
	public void onKeyEvent(KeyInputEvent event) {

		if (CoflSky.keyBindings[0].isPressed()) {
			if (WSCommandHandler.lastOnClickEvent != null) {

				String command = WSCommandHandler.lastOnClickEvent;
				WSCommandHandler.lastOnClickEvent = null;
				WSCommandHandler.HandleCommand(
						new JsonStringCommand(CommandType.EXECUTE, WSClient.gson.toJson(command)),
						Minecraft.getMinecraft().thePlayer);
			}

		}
		if(CoflSky.keyBindings[1].isKeyDown()) {
			if((System.currentTimeMillis() - LastClick) >= 300) {
						
				Flip f = WSCommandHandler.flipHandler.fds.getHighestFlip();
				
				if(f != null) {
					WSCommandHandler.Execute("/viewauction " + f.id, null);
					LastClick = System.currentTimeMillis();		
					String command =  WSClient.gson.toJson("/viewauction " + f.id);
					WSCommandHandler.flipHandler.fds.invalidateFlip(f);
					
					WSCommandHandler.Execute("/cofl track besthotkey " + f.id, Minecraft.getMinecraft().thePlayer);
					CoflSky.Wrapper.sendMessage(new JsonStringCommand(CommandType.CLICKED, command));
				} else {
					// only display message once (if this is the key down event)
					if(CoflSky.keyBindings[1].isPressed())
						WSCommandHandler.Execute("/cofl dialog nobestflip", Minecraft.getMinecraft().thePlayer);
				}
			}
		}
	}

	@SideOnly(Side.CLIENT)
	//@SubscribeEvent
	public void DrawOntoGUI(RenderGameOverlayEvent rgoe) {

		if (rgoe.type == ElementType.CROSSHAIRS) {
			Minecraft mc = Minecraft.getMinecraft();
			mc.ingameGUI.drawString(Minecraft.getMinecraft().fontRendererObj, "Flips in Pipeline:" + WSCommandHandler.flipHandler.fds.getFlipsSize(), 0, 0, Integer.MAX_VALUE);
		}
	}

	public static String ExtractUuidFromInventory(IInventory inventory) {

		ItemStack stack = inventory.getStackInSlot(13);
		if (stack != null) {
			try {
				String uuid = stack.serializeNBT().getCompoundTag("tag").getCompoundTag("ExtraAttributes")
						.getString("uuid");
				if (uuid.length() == 0) {
					throw new Exception();
				}
				CoflSky.logger.debug("Item has the UUID: " + uuid);
				return uuid;
			} catch (Exception e) {
				CoflSky.logger.error("Clicked item " + stack.getDisplayName() + " has the following meta: "
						+ stack.serializeNBT());
			}
		}
		return "";
	}

	public static final ItemStack GOLD_NUGGET = new ItemStack(
			Item.itemRegistry.getObject(new ResourceLocation("minecraft:gold_nugget")));

	public static final Pair<String, Pair<String, LocalDateTime>> EMPTY = Pair.of(null, Pair.of("",LocalDateTime.MIN));
	public static Pair<String, Pair<String, LocalDateTime>> last = EMPTY;
	
	@SubscribeEvent
	public void HandleChatEvent(ClientChatReceivedEvent sce) {
		if(CoflSky.Wrapper.isRunning && Configuration.getInstance().collectChat) {
			chatThreadPool.submit(() -> {
				String msg = sce.message.getUnformattedText();
				Matcher matcher = chatPattern.matcher(msg);
				boolean matchFound = matcher.find();
				if (matchFound) {
					Command<String[]> data = new Command<>(CommandType.CHAT_BATCH, new  String[]{msg});
					CoflSky.Wrapper.sendMessage(data);
				}
			});
		}
		
	}
	
	public static long lastStartTime = Long.MIN_VALUE;
	
	public static long LastViewAuctionInvocation = Long.MIN_VALUE;
	public static String LastViewAuctionUUID =null;
		
	@SideOnly(Side.CLIENT)
	@SubscribeEvent
	public void OnGuiClick(GuiScreenEvent.MouseInputEvent mie) {
		if (!CoflSky.Wrapper.isRunning) return;
		if (!(mie.gui instanceof GuiChest)) return; // verify that it's really a chest
		if (!(((GuiChest) mie.gui).inventorySlots instanceof ContainerChest)) return;
		ContainerChest chest = (ContainerChest) ((GuiChest) mie.gui).inventorySlots;
		IInventory inv = chest.getLowerChestInventory();
		if (inv.hasCustomName()) { // verify that the chest actually has a custom name
			String chestName = inv.getName();
			if (chestName.equalsIgnoreCase("BIN Auction View")) {

				ItemStack heldItem = Minecraft.getMinecraft().thePlayer.inventory.getItemStack();

				if (heldItem != null) {
					CoflSky.logger.debug("Clicked on: " + heldItem.getItem().getRegistryName());

					String itemUUID = ExtractUuidFromInventory(inv);

					if(System.currentTimeMillis() > lastStartTime) {

						if (heldItem.isItemEqual(GOLD_NUGGET)) {
							AuctionData ad = new AuctionData();
							ad.setItemId(itemUUID);

							if((LastViewAuctionInvocation+60*1000) >=  System.currentTimeMillis()) {
								ad.setAuctionId(LastViewAuctionUUID);
							} else {
								ad.setAuctionId("");
							}

							Command<AuctionData> data = new Command<>(CommandType.PURCHASE_START, ad);
							CoflSky.Wrapper.sendMessage(data);
							CoflSky.logger.debug("PurchaseStart");
							last = Pair.of("You claimed ", Pair.of(itemUUID, LocalDateTime.now()));
							lastStartTime = System.currentTimeMillis() + 200 /*ensure a small debounce*/;
						}
					}
				}
			}
		}
	}

	@SubscribeEvent
    public void OnRenderTick(TickEvent.RenderTickEvent event) {
		CountdownTimer.onRenderTick(event);
	}

	int UpdateThisTick = 0;
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onTick(TickEvent.ClientTickEvent event) {
		UpdateThisTick++;
		if (UpdateThisTick >= 200) UpdateThisTick = 0;
		if (UpdateThisTick == 0) {
			tickThreadPool.submit(() -> {
				ScoreboardData();
				TabMenuData();
			});
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onGuiOpen(GuiOpenEvent event) {
		if (!config.extendedTooltips) return;
		if(descriptionHandler != null)
			descriptionHandler.Close();
		if(event.gui == null)
			emptyTooltipData();

		if (!(event.gui instanceof GuiContainer)) return;
		new Thread(() -> {
			try {
				descriptionHandler = new DescriptionHandler();
				descriptionHandler.loadDescriptionAndListenForChanges(event);
			} catch (Exception e)
			{
				CoflSky.logger.error("failed to update description " + e);
			}
		}).start();
	}
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onItemTooltipEvent(ItemTooltipEvent event) {
		if (!config.extendedTooltips) return;
		if(descriptionHandler == null) return;
		descriptionHandler.setTooltips(event);
	}
}
