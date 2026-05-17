package com.simibubi.create.content.trains.track.placement;

import com.simibubi.create.content.trains.track.ITrackBlock;

import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class TrackEndpoint {
	final BlockPos pos;
	final BlockState state;
	final Vec3 axis;
	final Vec3 normedAxis;
	final Vec3 end;
	final Vec3 normal;

	private TrackEndpoint(BlockPos pos, BlockState state, Vec3 axis, Vec3 end, Vec3 normal) {
		this.pos = pos;
		this.state = state;
		this.axis = axis;
		this.normedAxis = axis.normalize();
		this.end = end;
		this.normal = normal;
	}

	static TrackEndpoint fromSelection(Level level, TrackPlacement.ConnectingFrom connectingFrom) {
		BlockPos pos = connectingFrom.pos();
		return new TrackEndpoint(pos, level.getBlockState(pos), connectingFrom.axis(), connectingFrom.end(),
			connectingFrom.normal());
	}

	static TrackEndpoint fromTarget(TrackPlacementRequest request) {
		ITrackBlock track = request.targetTrack();
		Pair<Vec3, AxisDirection> nearestTrackAxis =
			track.getNearestTrackAxis(request.level(), request.targetPos(), request.targetState(), request.lookVec());
		Vec3 axis = nearestTrackAxis.getFirst()
			.scale(nearestTrackAxis.getSecond() == AxisDirection.POSITIVE ? -1 : 1);
		Vec3 normal = track.getUpNormal(request.level(), request.targetPos(), request.targetState()).normalize();
		Vec3 end = track.getCurveStart(request.level(), request.targetPos(), request.targetState(), axis);
		return new TrackEndpoint(request.targetPos(), request.targetState(), axis, end, normal);
	}

	TrackEndpoint flip(ITrackBlock track, Level level) {
		Vec3 flippedAxis = axis.scale(-1);
		return new TrackEndpoint(pos, state, flippedAxis, track.getCurveStart(level, pos, state, flippedAxis), normal);
	}
}
