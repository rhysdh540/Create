package com.simibubi.create.content.trains.track.placement;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;

import net.createmod.catnip.data.Couple;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class TrackPlacementPlanner {

	record PlannedPlacement(TrackPlacement.PlacementInfo info, BlockState state1, BlockState state2,
		BlockPos targetPos1, BlockPos targetPos2) {
	}

	private TrackPlacementPlanner() {}

	static PlannedPlacement plan(TrackPlacementRequest request, TrackPlacement.PlacementInfo info) {
		TrackEndpoint end2 = TrackEndpoint.fromTarget(request);
		TrackPlacement.ConnectingFrom connectingFrom = request.stack().get(com.simibubi.create.AllDataComponents.TRACK_CONNECTING_FROM);
		TrackEndpoint end1 = TrackEndpoint.fromSelection(request.level(), connectingFrom);

		applyPreviewEndpoints(info, end1, end2, request.level().isClientSide);

		int maxLength = com.simibubi.create.infrastructure.config.AllConfigs.server().trains.maxTrackPlacementLength.get();
		if (end1.pos.equals(end2.pos))
			return new PlannedPlacement(info.withMessage("second_point"), end1.state, end2.state, end1.pos, end2.pos);
		if (end1.pos.distSqr(end2.pos) > maxLength * maxLength)
			return new PlannedPlacement(info.withMessage("too_far").tooJumbly(), end1.state, end2.state, end1.pos, end2.pos);
		if (!end1.state.hasProperty(TrackBlock.HAS_BE))
			return new PlannedPlacement(info.withMessage("original_missing"), end1.state, end2.state, end1.pos, end2.pos);
		if (request.level().getBlockEntity(end2.pos) instanceof TrackBlockEntity tbe && tbe.isTilted())
			return new PlannedPlacement(info.withMessage("turn_start"), end1.state, end2.state, end1.pos, end2.pos);

		ITrackBlock targetTrack = request.targetTrack();
		if (end1.axis.dot(end2.end.subtract(end1.end)) < 0) {
			end1 = end1.flip(targetTrack, request.level());
			updatePreviewStart(info, end1, request.level().isClientSide);
		}

		double[] intersect = VecHelper.intersect(end1.end, end2.end, end1.normedAxis, end2.normedAxis, Axis.Y);
		boolean parallel = intersect == null;
		boolean skipCurve = false;

		if ((parallel && end1.normedAxis.dot(end2.normedAxis) > 0)
			|| (!parallel && (intersect[0] < 0 || intersect[1] < 0))) {
			end2 = end2.flip(targetTrack, request.level());
			updatePreviewEnd(info, end2, request.level().isClientSide);
		}

		Vec3 cross2 = end2.normedAxis.cross(new Vec3(0, 1, 0));
		double angle = Mth.atan2(end2.normedAxis.z, end2.normedAxis.x) - Mth.atan2(end1.normedAxis.z, end1.normedAxis.x);
		double ascend = end2.end.subtract(end1.end).y;
		double absAscend = Math.abs(ascend);
		boolean slope = !end1.normal.equals(end2.normal);

		if (request.level().isClientSide)
			info.curve = createCurve(end1, end2, info, request);

		double dist = 0;
		if (parallel) {
			double[] sTest = VecHelper.intersect(end1.end, end2.end, end1.normedAxis, cross2, Axis.Y);
			if (sTest != null) {
				double t = Math.abs(sTest[0]);
				double u = Math.abs(sTest[1]);

				skipCurve = Mth.equal(u, 0);

				if (!skipCurve && sTest[0] < 0)
					return new PlannedPlacement(info.withMessage("perpendicular").tooJumbly(), end1.state, end2.state, end1.pos, end2.pos);

				if (skipCurve) {
					dist = VecHelper.getCenterOf(end1.pos).distanceTo(VecHelper.getCenterOf(end2.pos));
					info.end1Extent = (int) Math.round((dist + 1) / end1.axis.length());
				} else {
					if (!Mth.equal(ascend, 0) || end1.normedAxis.y != 0)
						return new PlannedPlacement(info.withMessage("ascending_s_curve"), end1.state, end2.state, end1.pos, end2.pos);

					double targetT = u <= 1 ? 3 : u * 2;
					if (t < targetT)
						return new PlannedPlacement(info.withMessage("too_sharp"), end1.state, end2.state, end1.pos, end2.pos);

					if (t > targetT) {
						int correction = (int) ((t - targetT) / end1.axis.length());
						info.end1Extent = request.maximiseTurn() ? 0 : correction / 2 + (correction % 2);
						info.end2Extent = request.maximiseTurn() ? 0 : correction / 2;
					}
				}
			}
		}

		if (slope) {
			if (!skipCurve)
				return new PlannedPlacement(info.withMessage("slope_turn"), end1.state, end2.state, end1.pos, end2.pos);
			if (Mth.equal(end1.normal.dot(end2.normal), 0))
				return new PlannedPlacement(info.withMessage("opposing_slopes"), end1.state, end2.state, end1.pos, end2.pos);
			if ((end1.axis.y < 0 || end2.axis.y > 0) && ascend > 0)
				return new PlannedPlacement(info.withMessage("leave_slope_ascending"), end1.state, end2.state, end1.pos, end2.pos);
			if ((end1.axis.y > 0 || end2.axis.y < 0) && ascend < 0)
				return new PlannedPlacement(info.withMessage("leave_slope_descending"), end1.state, end2.state, end1.pos, end2.pos);

			skipCurve = false;
			info.end1Extent = 0;
			info.end2Extent = 0;

			Axis plane = Mth.equal(end1.axis.x, 0) ? Axis.X : Axis.Z;
			intersect = VecHelper.intersect(end1.end, end2.end, end1.normedAxis, end2.normedAxis, plane);
			double dist1 = Math.abs(intersect[0] / end1.axis.length());
			double dist2 = Math.abs(intersect[1] / end2.axis.length());

			if (dist1 > dist2)
				info.end1Extent = (int) Math.round(dist1 - dist2);
			if (dist2 > dist1)
				info.end2Extent = (int) Math.round(dist2 - dist1);

			double turnSize = Math.min(dist1, dist2);
			if (intersect[0] < 0 || intersect[1] < 0)
				return new PlannedPlacement(info.withMessage("too_sharp").tooJumbly(), end1.state, end2.state, end1.pos, end2.pos);
			if (turnSize < 2)
				return new PlannedPlacement(info.withMessage("too_sharp"), end1.state, end2.state, end1.pos, end2.pos);

			if (turnSize > 2 && !request.maximiseTurn()) {
				info.end1Extent += turnSize - 2;
				info.end2Extent += turnSize - 2;
			}
		}

		if (skipCurve && !Mth.equal(ascend, 0)) {
			int hDistance = info.end1Extent;
			if (end1.axis.y == 0 || !Mth.equal(absAscend + 1, dist / end1.axis.length())) {
				if (end1.axis.y != 0 && end1.axis.y == -end2.axis.y)
					return new PlannedPlacement(info.withMessage("ascending_s_curve"), end1.state, end2.state, end1.pos, end2.pos);

				info.end1Extent = 0;
				double minHDistance = Math.max(absAscend < 4 ? absAscend * 4 : absAscend * 3, 6) / end1.axis.length();
				if (hDistance < minHDistance)
					return new PlannedPlacement(info.withMessage("too_steep"), end1.state, end2.state, end1.pos, end2.pos);
				if (hDistance > minHDistance) {
					int correction = (int) (hDistance - minHDistance);
					info.end1Extent = request.maximiseTurn() ? 0 : correction / 2 + (correction % 2);
					info.end2Extent = request.maximiseTurn() ? 0 : correction / 2;
				}
				skipCurve = false;
			}
		}

		if (!parallel) {
			float absAngle = Math.abs(AngleHelper.deg(angle));
			if (absAngle < 60 || absAngle > 300)
				return new PlannedPlacement(info.withMessage("turn_90").tooJumbly(), end1.state, end2.state, end1.pos, end2.pos);

			intersect = VecHelper.intersect(end1.end, end2.end, end1.normedAxis, end2.normedAxis, Axis.Y);
			double dist1 = Math.abs(intersect[0]);
			double dist2 = Math.abs(intersect[1]);
			float ex1 = 0;
			float ex2 = 0;

			if (dist1 > dist2)
				ex1 = (float) ((dist1 - dist2) / end1.axis.length());
			if (dist2 > dist1)
				ex2 = (float) ((dist2 - dist1) / end2.axis.length());

			double turnSize = Math.min(dist1, dist2) - .1d;
			boolean ninety = (absAngle + .25f) % 90 < 1;

			if (intersect[0] < 0 || intersect[1] < 0)
				return new PlannedPlacement(info.withMessage("too_sharp").tooJumbly(), end1.state, end2.state, end1.pos, end2.pos);

			double minTurnSize = ninety ? 7 : 3.25;
			double turnSizeToFitAscend =
				minTurnSize + (ninety ? Math.max(0, absAscend - 3) * 2f : Math.max(0, absAscend - 1.5f) * 1.5f);

			if (turnSize < minTurnSize)
				return new PlannedPlacement(info.withMessage("too_sharp"), end1.state, end2.state, end1.pos, end2.pos);
			if (turnSize < turnSizeToFitAscend)
				return new PlannedPlacement(info.withMessage("too_steep"), end1.state, end2.state, end1.pos, end2.pos);

			if (!request.maximiseTurn()) {
				ex1 += (turnSize - turnSizeToFitAscend) / end1.axis.length();
				ex2 += (turnSize - turnSizeToFitAscend) / end2.axis.length();
			}
			info.end1Extent = Mth.floor(ex1);
			info.end2Extent = Mth.floor(ex2);
		}

		Vec3 offset1 = end1.axis.scale(info.end1Extent);
		Vec3 offset2 = end2.axis.scale(info.end2Extent);
		BlockPos targetPos1 = end1.pos.offset(BlockPos.containing(offset1));
		BlockPos targetPos2 = end2.pos.offset(BlockPos.containing(offset2));

		info.curve = skipCurve ? null : createCurve(end1, end2, info, request);
		info.valid = true;
		info.pos1 = end1.pos;
		info.pos2 = end2.pos;
		info.axis1 = end1.axis;
		info.axis2 = end2.axis;
		return new PlannedPlacement(info, end1.state, end2.state, targetPos1, targetPos2);
	}

	private static void applyPreviewEndpoints(TrackPlacement.PlacementInfo info, TrackEndpoint start, TrackEndpoint end,
		boolean clientSide) {
		if (!clientSide)
			return;
		info.end1 = start.end;
		info.end2 = end.end;
		info.normal1 = start.normal;
		info.normal2 = end.normal;
		info.axis1 = start.axis;
		info.axis2 = end.axis;
	}

	private static void updatePreviewStart(TrackPlacement.PlacementInfo info, TrackEndpoint start, boolean clientSide) {
		if (!clientSide)
			return;
		info.end1 = start.end;
		info.axis1 = start.axis;
	}

	private static void updatePreviewEnd(TrackPlacement.PlacementInfo info, TrackEndpoint end, boolean clientSide) {
		if (!clientSide)
			return;
		info.end2 = end.end;
		info.axis2 = end.axis;
	}

	private static BezierConnection createCurve(TrackEndpoint start, TrackEndpoint end, TrackPlacement.PlacementInfo info,
		TrackPlacementRequest request) {
		Vec3 offset1 = start.axis.scale(info.end1Extent);
		Vec3 offset2 = end.axis.scale(info.end2Extent);
		BlockPos targetPos1 = start.pos.offset(BlockPos.containing(offset1));
		BlockPos targetPos2 = end.pos.offset(BlockPos.containing(offset2));
		return new BezierConnection(Couple.create(targetPos1, targetPos2),
			Couple.create(start.end.add(offset1), end.end.add(offset2)),
			Couple.create(start.normedAxis, end.normedAxis),
			Couple.create(start.normal, end.normal), true, request.girder(), request.trackMaterial());
	}
}
