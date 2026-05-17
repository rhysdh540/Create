package com.simibubi.create.content.trains.track.placement;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllTags;
import com.simibubi.create.AllTags.AllItemTags;

import net.createmod.catnip.data.Iterate;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

final class TrackPlacementItemRequirements {

	private TrackPlacementItemRequirements() {}

	static TrackPlacement.PlacementInfo verifyOrConsume(Level level, Player player, ItemStack trackStack,
		ItemStack offhandItem, TrackPlacement.PlacementInfo info) {
		BlockItem paveItem = getPavingItem(offhandItem);

		for (boolean simulate : Iterate.trueAndFalse) {
			if (level.isClientSide && !simulate)
				break;

			int tracks = info.requiredTracks;
			int pavement = info.requiredPavement;
			int foundTracks = 0;
			int foundPavement = 0;

			Inventory inv = player.getInventory();
			int size = inv.items.size();
			for (int j = 0; j <= size + 1; j++) {
				int i = j;
				boolean offhand = j == size + 1;
				if (j == size)
					i = inv.selected;
				else if (offhand)
					i = 0;
				else if (j == inv.selected)
					continue;

				ItemStack stackInSlot = (offhand ? inv.offhand : inv.items).get(i);
				boolean isTrack = AllTags.AllBlockTags.TRACKS.matches(stackInSlot) && stackInSlot.is(trackStack.getItem());
				if (!isTrack && (paveItem == null || !stackInSlot.is(paveItem.asItem())))
					continue;
				if (isTrack ? foundTracks >= tracks : foundPavement >= pavement)
					continue;

				int count = stackInSlot.getCount();

				if (!simulate) {
					int remainingItems =
						count - Math.min(isTrack ? tracks - foundTracks : pavement - foundPavement, count);
					if (i == inv.selected)
						stackInSlot.remove(AllDataComponents.TRACK_CONNECTING_FROM);
					ItemStack newItem = stackInSlot.copyWithCount(remainingItems);
					if (offhand)
						player.setItemInHand(InteractionHand.OFF_HAND, newItem);
					else
						inv.setItem(i, newItem);
				}

				if (isTrack)
					foundTracks += count;
				else
					foundPavement += count;
			}

			if (simulate && foundTracks < tracks) {
				info.valid = false;
				info.tooJumbly();
				info.hasRequiredTracks = false;
				return info.withMessage("not_enough_tracks");
			}

			if (simulate && foundPavement < pavement) {
				info.valid = false;
				info.tooJumbly();
				info.hasRequiredPavement = false;
				return info.withMessage("not_enough_pavement");
			}
		}

		return info;
	}

	static BlockItem getPavingItem(ItemStack offhandItem) {
		if (offhandItem.getItem() instanceof BlockItem blockItem && !AllItemTags.INVALID_FOR_TRACK_PAVING.matches(offhandItem))
			return blockItem;
		return null;
	}

}
