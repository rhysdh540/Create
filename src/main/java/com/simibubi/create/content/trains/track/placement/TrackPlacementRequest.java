package com.simibubi.create.content.trains.track.placement;

import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

record TrackPlacementRequest(Level level, BlockPos targetPos, BlockState targetState, ItemStack stack, boolean girder,
	boolean maximiseTurn, Vec3 lookVec) {

	TrackMaterial trackMaterial() {
		return TrackMaterial.fromItem(stack.getItem());
	}

	ITrackBlock targetTrack() {
		return (ITrackBlock) targetState.getBlock();
	}

}
