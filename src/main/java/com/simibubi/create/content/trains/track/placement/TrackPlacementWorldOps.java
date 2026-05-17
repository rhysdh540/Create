package com.simibubi.create.content.trains.track.placement;

import java.util.HashSet;
import java.util.Set;

import com.simibubi.create.AllTags;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackPaver;
import com.simibubi.create.content.trains.track.TrackShape;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;
import com.simibubi.create.foundation.utility.BlockHelper;

import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class TrackPlacementWorldOps {

	private TrackPlacementWorldOps() {}

	static void paveTracks(Level level, TrackPlacement.PlacementInfo info, BlockItem blockItem, boolean simulate) {
		Block block = blockItem.getBlock();
		info.requiredPavement = 0;
		if (block instanceof EntityBlock || block.defaultBlockState().getCollisionShape(level, info.pos1).isEmpty())
			return;

		Set<BlockPos> visited = new HashSet<>();

		for (boolean first : Iterate.trueAndFalse) {
			int extent = (first ? info.end1Extent : info.end2Extent) + (info.curve != null ? 1 : 0);
			Vec3 axis = first ? info.axis1 : info.axis2;
			BlockPos pavePos = first ? info.pos1 : info.pos2;
			info.requiredPavement +=
				TrackPaver.paveStraight(level, pavePos.below(), axis, extent, block, simulate, visited);
		}

		if (info.curve != null)
			info.requiredPavement += TrackPaver.paveCurve(level, info.curve, block, simulate, visited);
	}

	static TrackPlacement.PlacementInfo placeTracks(Level level, TrackPlacement.PlacementInfo info, BlockState state1,
		BlockState state2, BlockPos targetPos1, BlockPos targetPos2, boolean simulate) {
		info.requiredTracks = 0;

		for (boolean first : Iterate.trueAndFalse) {
			int extent = first ? info.end1Extent : info.end2Extent;
			Vec3 axis = first ? info.axis1 : info.axis2;
			BlockPos pos = first ? info.pos1 : info.pos2;
			BlockState state = first ? state1 : state2;
			if (state.hasProperty(TrackBlock.HAS_BE) && !simulate)
				state = state.setValue(TrackBlock.HAS_BE, false);

			switch (state.getValue(TrackBlock.SHAPE)) {
				case TE, TW:
					state = state.setValue(TrackBlock.SHAPE, TrackShape.XO);
					break;
				case TN, TS:
					state = state.setValue(TrackBlock.SHAPE, TrackShape.ZO);
					break;
				default:
					break;
			}

			for (int i = 0; i < (info.curve != null ? extent + 1 : extent); i++) {
				Vec3 offset = axis.scale(i);
				BlockPos offsetPos = pos.offset(BlockPos.containing(offset));
				BlockState stateAtPos = level.getBlockState(offsetPos);
				// Copy shared properties from the shaped state onto the selected track material block.
				BlockState toPlace = BlockHelper.copyProperties(state, info.trackMaterial.getBlock().defaultBlockState());

				boolean canPlace = stateAtPos.canBeReplaced() || stateAtPos.is(BlockTags.FLOWERS);
				if (canPlace)
					info.requiredTracks++;
				if (simulate)
					continue;

				if (stateAtPos.getBlock() instanceof ITrackBlock trackAtPos) {
					toPlace = trackAtPos.overlay(level, offsetPos, stateAtPos, toPlace);
					canPlace = true;
				}

				if (canPlace)
					level.setBlock(offsetPos, ProperWaterloggedBlock.withWater(level, toPlace, offsetPos), Block.UPDATE_ALL);
			}
		}

		if (info.curve == null)
			return info;

		if (!simulate) {
			BlockState onto = info.trackMaterial.getBlock().defaultBlockState();
			BlockState stateAtPos = level.getBlockState(targetPos1);
			level.setBlock(targetPos1, ProperWaterloggedBlock.withWater(level,
				(AllTags.AllBlockTags.TRACKS.matches(stateAtPos) ? stateAtPos : BlockHelper.copyProperties(state1, onto))
					.setValue(TrackBlock.HAS_BE, true), targetPos1), Block.UPDATE_ALL);

			stateAtPos = level.getBlockState(targetPos2);
			level.setBlock(targetPos2, ProperWaterloggedBlock.withWater(level,
				(AllTags.AllBlockTags.TRACKS.matches(stateAtPos) ? stateAtPos : BlockHelper.copyProperties(state2, onto))
					.setValue(TrackBlock.HAS_BE, true), targetPos2), Block.UPDATE_ALL);
		}

		BlockEntity te1 = level.getBlockEntity(targetPos1);
		BlockEntity te2 = level.getBlockEntity(targetPos2);
		int requiredTracksForTurn = (info.curve.getSegmentCount() + 1) / 2;

		if (!(te1 instanceof TrackBlockEntity tte1) || !(te2 instanceof TrackBlockEntity tte2)) {
			info.requiredTracks += requiredTracksForTurn;
			return info;
		}

		if (!tte1.getConnections()
			.containsKey(tte2.getBlockPos()))
			info.requiredTracks += requiredTracksForTurn;

		if (simulate)
			return info;

		tte1.addConnection(info.curve);
		tte2.addConnection(info.curve.secondary());
		tte1.tilt.tryApplySmoothing();
		tte2.tilt.tryApplySmoothing();
		return info;
	}

}
