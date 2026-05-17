package com.simibubi.create.content.trains.track.placement;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;

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

	private enum CandidateFamily {
		STRAIGHT,
		S_BEND,
		RISING_STRAIGHT,
		SLOPE,
		TURN
	}

	private static final class PlanningContext {
		final TrackPlacementRequest request;
		final TrackPlacement.PlacementInfo info;
		final ITrackBlock targetTrack;

		TrackEndpoint start;
		TrackEndpoint end;
		boolean parallel;
		boolean slope;
		double ascend;
		double absAscend;
		double angle;

		private PlanningContext(TrackPlacementRequest request, TrackPlacement.PlacementInfo info, ITrackBlock targetTrack,
			TrackEndpoint start, TrackEndpoint end) {
			this.request = request;
			this.info = info;
			this.targetTrack = targetTrack;
			this.start = start;
			this.end = end;
		}

		PlannedPlacement fail(String message, boolean tooJumbly) {
			if (tooJumbly)
				info.tooJumbly();
			return new PlannedPlacement(info.withMessage(message), start.state, end.state, start.pos, end.pos);
		}
	}

	private record ConnectionCandidate(
		TrackEndpoint start, TrackEndpoint end,
		CandidateFamily family, boolean hasCurve,
		int end1Extent, int end2Extent, int sourceEnd1Extent,
		double straightDistance,
		double lateralT, double lateralTarget, double[] lateralIntersection,
		double[] turnIntersection, double[] slopeIntersection, double turnSize,
		double minTurnSize, double turnSizeToFitAscend, float absAngle,
		boolean nonStraightBase, boolean naturalSlope, boolean opposingSlopeTransition,
		double minHorizontalDistance
	) {
		BlockPos targetPos1() {
			return start.pos.offset(BlockPos.containing(start.axis.scale(end1Extent)));
		}

		BlockPos targetPos2() {
			return end.pos.offset(BlockPos.containing(end.axis.scale(end2Extent)));
		}
	}

	private TrackPlacementPlanner() {}

	static PlannedPlacement plan(TrackPlacementRequest request, TrackPlacement.PlacementInfo info) {
		TrackEndpoint end = TrackEndpoint.fromTarget(request);
		TrackPlacement.ConnectingFrom connectingFrom = request.stack().get(AllDataComponents.TRACK_CONNECTING_FROM);
		TrackEndpoint start = TrackEndpoint.fromSelection(request.level(), connectingFrom);
		PlanningContext context = new PlanningContext(request, info, request.targetTrack(), start, end);

		applyPreviewEndpoints(info, start, end, request.level().isClientSide);

		PlannedPlacement failure = validateInitialConditions(context);
		if (failure != null)
			return failure;

		orientStart(context);
		orientEnd(context);
		updateGeometry(context);

		ConnectionCandidate candidate = buildCandidate(context);
		applyPreviewCandidate(info, candidate, request);

		failure = validateCandidate(context, candidate);
		if (failure != null)
			return failure;

		return applyCandidate(context, candidate);
	}

	private static PlannedPlacement validateInitialConditions(PlanningContext context) {
		int maxLength = AllConfigs.server().trains.maxTrackPlacementLength.get();
		if (context.start.pos.equals(context.end.pos))
			return context.fail("second_point", false);
		if (context.start.pos.distSqr(context.end.pos) > maxLength * maxLength)
			return context.fail("too_far", true);
		if (!context.start.state.hasProperty(TrackBlock.HAS_BE))
			return context.fail("original_missing", false);
		if (context.request.level().getBlockEntity(context.end.pos) instanceof TrackBlockEntity tbe && tbe.isTilted())
			return context.fail("turn_start", false);
		return null;
	}

	private static void orientStart(PlanningContext context) {
		if (context.start.axis.dot(context.end.end.subtract(context.start.end)) < 0) {
			context.start = context.start.flip(context.targetTrack, context.request.level());
			updatePreviewStart(context.info, context.start, context.request.level().isClientSide);
		}
	}

	private static void orientEnd(PlanningContext context) {
		double[] intersection = horizontalIntersection(context.start, context.end);
		boolean parallel = intersection == null;
		if ((parallel && context.start.normedAxis.dot(context.end.normedAxis) > 0)
			|| (!parallel && (intersection[0] < 0 || intersection[1] < 0))) {
			context.end = context.end.flip(context.targetTrack, context.request.level());
			updatePreviewEnd(context.info, context.end, context.request.level().isClientSide);
		}
	}

	private static void updateGeometry(PlanningContext context) {
		context.parallel = horizontalIntersection(context.start, context.end) == null;
		context.angle = Mth.atan2(context.end.normedAxis.z, context.end.normedAxis.x)
			- Mth.atan2(context.start.normedAxis.z, context.start.normedAxis.x);
		context.ascend = context.end.end.subtract(context.start.end).y;
		context.absAscend = Math.abs(context.ascend);
		context.slope = !context.start.normal.equals(context.end.normal);
	}

	private static ConnectionCandidate buildCandidate(PlanningContext context) {
		ConnectionCandidate base = context.parallel ? buildParallelCandidate(context) : buildTurnCandidate(context);
		// slopes/rising straights refine the previously flat base
		if (context.slope)
			return buildSlopeCandidate(context, base);
		if (base.family == CandidateFamily.STRAIGHT && !Mth.equal(context.ascend, 0))
			return buildRisingStraightCandidate(context, base);
		return base;
	}

	// parallel: either straight or s-bend
	private static ConnectionCandidate buildParallelCandidate(PlanningContext context) {
		Vec3 perpendicularToEnd = context.end.normedAxis.cross(new Vec3(0, 1, 0));
		double[] lateralIntersection =
			VecHelper.intersect(context.start.end, context.end.end, context.start.normedAxis, perpendicularToEnd, Axis.Y);
		double t = Math.abs(lateralIntersection[0]);
		double u = Math.abs(lateralIntersection[1]);
		// u == 0 when the two ends are collinear
		if (Mth.equal(u, 0)) {
			double straightDistance = VecHelper.getCenterOf(context.start.pos)
				.distanceTo(VecHelper.getCenterOf(context.end.pos));
			int end1Extent = (int) Math.round((straightDistance + 1) / context.start.axis.length());
			return new ConnectionCandidate(context.start, context.end, CandidateFamily.STRAIGHT, false, end1Extent, 0,
				end1Extent, straightDistance, t, 0, lateralIntersection, null, null, 0, 0, 0, 0, false, false, false, 0);
		}

		double lateralTarget = u <= 1 ? 3 : u * 2;
		int correction = Math.max(0, (int) ((t - lateralTarget) / context.start.axis.length()));
		int[] extents = symmetricCorrection(context.request, correction);
		return new ConnectionCandidate(context.start, context.end, CandidateFamily.S_BEND, true, extents[0], extents[1], 0,
			0, t, lateralTarget, lateralIntersection, null, null, 0, 0, 0, 0, false, false, false, 0);
	}

	private static ConnectionCandidate buildSlopeCandidate(PlanningContext context, ConnectionCandidate baseCandidate) {
		// only straight bases have a single vertical-plane slope solution
		if (baseCandidate.family != CandidateFamily.STRAIGHT)
			return new ConnectionCandidate(context.start, context.end, CandidateFamily.SLOPE, baseCandidate.hasCurve,
				baseCandidate.end1Extent, baseCandidate.end2Extent, 0, 0, baseCandidate.lateralT, baseCandidate.lateralTarget,
				baseCandidate.lateralIntersection, baseCandidate.turnIntersection, null, baseCandidate.turnSize, 2,
				baseCandidate.turnSizeToFitAscend, baseCandidate.absAngle, true, false, false, 0);

		// straight bases can be refined by intersecting both rails in the shared vertical plane
		Axis plane = Mth.equal(context.start.axis.x, 0) ? Axis.X : Axis.Z;
		double[] slopeIntersection =
			VecHelper.intersect(context.start.end, context.end.end, context.start.normedAxis, context.end.normedAxis, plane);
		double dist1 = Math.abs(slopeIntersection[0] / context.start.axis.length());
		double dist2 = Math.abs(slopeIntersection[1] / context.end.axis.length());
		int end1Extent = dist1 > dist2 ? (int) Math.round(dist1 - dist2) : 0;
		int end2Extent = dist2 > dist1 ? (int) Math.round(dist2 - dist1) : 0;
		double turnSize = Math.min(dist1, dist2);
		if (turnSize > 2 && !context.request.maximiseTurn()) {
			end1Extent += turnSize - 2;
			end2Extent += turnSize - 2;
		}

		return new ConnectionCandidate(context.start, context.end, CandidateFamily.SLOPE, true, end1Extent, end2Extent, 0,
			0, 0, 0, baseCandidate.lateralIntersection, baseCandidate.turnIntersection, slopeIntersection, turnSize, 2, 0,
			0, false, false, false, 0);
	}

	private static ConnectionCandidate buildRisingStraightCandidate(PlanningContext context, ConnectionCandidate baseCandidate) {
		// vertical offset on a rising straight either stays "naturally" straight or becomes a transition
		boolean naturalSlope = context.start.axis.y != 0
			&& Mth.equal(context.absAscend + 1, baseCandidate.straightDistance / context.start.axis.length());
		boolean opposingSlopeTransition = context.start.axis.y != 0 && context.start.axis.y == -context.end.axis.y;
		double minHorizontalDistance =
			Math.max(context.absAscend < 4 ? context.absAscend * 4 : context.absAscend * 3, 6) / context.start.axis.length();
		int[] extents = naturalSlope ? new int[] {baseCandidate.end1Extent, 0}
			: symmetricCorrection(context.request, Math.max(0, (int) (baseCandidate.end1Extent - minHorizontalDistance)));
		return new ConnectionCandidate(context.start, context.end, CandidateFamily.RISING_STRAIGHT, !naturalSlope,
			extents[0], extents[1], baseCandidate.end1Extent, baseCandidate.straightDistance, 0, 0,
			baseCandidate.lateralIntersection, null, null, 0, 0, 0, 0, false, naturalSlope, opposingSlopeTransition,
			minHorizontalDistance);
	}

	private static ConnectionCandidate buildTurnCandidate(PlanningContext context) {
		// turns are sized from the horizontal intersection and extended if the turn is wider than required
		double[] turnIntersection = horizontalIntersection(context.start, context.end);
		double dist1 = Math.abs(turnIntersection[0]);
		double dist2 = Math.abs(turnIntersection[1]);
		float ex1 = dist1 > dist2 ? (float) ((dist1 - dist2) / context.start.axis.length()) : 0;
		float ex2 = dist2 > dist1 ? (float) ((dist2 - dist1) / context.end.axis.length()) : 0;
		float absAngle = Math.abs(AngleHelper.deg(context.angle));
		boolean ninety = (absAngle + .25f) % 90 < 1;
		double turnSize = Math.min(dist1, dist2) - .1d;
		double minTurnSize = ninety ? 7 : 3.25;
		double turnSizeToFitAscend = minTurnSize + (ninety ? Math.max(0, context.absAscend - 3) * 2f
			: Math.max(0, context.absAscend - 1.5f) * 1.5f);
		if (!context.request.maximiseTurn()) {
			ex1 += Math.max(0, turnSize - turnSizeToFitAscend) / context.start.axis.length();
			ex2 += Math.max(0, turnSize - turnSizeToFitAscend) / context.end.axis.length();
		}

		return new ConnectionCandidate(context.start, context.end, CandidateFamily.TURN, true, Mth.floor(ex1), Mth.floor(ex2),
			0, 0, 0, 0, null, turnIntersection, null, turnSize, minTurnSize, turnSizeToFitAscend, absAngle, false, false,
			false, 0);
	}

	private static PlannedPlacement validateCandidate(PlanningContext context, ConnectionCandidate candidate) {
		return switch (candidate.family) {
			case STRAIGHT -> null;
			case S_BEND -> validateSBendCandidate(context, candidate);
			case RISING_STRAIGHT -> validateRisingStraightCandidate(context, candidate);
			case SLOPE -> validateSlopeCandidate(context, candidate);
			case TURN -> validateTurnCandidate(context, candidate);
		};
	}

	private static PlannedPlacement validateSBendCandidate(PlanningContext context, ConnectionCandidate candidate) {
		if (candidate.lateralIntersection[0] < 0)
			return context.fail("perpendicular", true);
		if (!Mth.equal(context.ascend, 0) || context.start.normedAxis.y != 0)
			return context.fail("ascending_s_curve", false);
		if (candidate.lateralT < candidate.lateralTarget)
			return context.fail("too_sharp", false);
		return null;
	}

	private static PlannedPlacement validateRisingStraightCandidate(PlanningContext context, ConnectionCandidate candidate) {
		if (candidate.naturalSlope)
			return null;
		if (candidate.opposingSlopeTransition)
			return context.fail("ascending_s_curve", false);
		if (candidate.sourceEnd1Extent < candidate.minHorizontalDistance)
			return context.fail("too_steep", false);
		return null;
	}

	private static PlannedPlacement validateSlopeCandidate(PlanningContext context, ConnectionCandidate candidate) {
		if (candidate.nonStraightBase)
			return context.fail("slope_turn", false);
		if (Mth.equal(context.start.normal.dot(context.end.normal), 0))
			return context.fail("opposing_slopes", false);
		if ((context.start.axis.y < 0 || context.end.axis.y > 0) && context.ascend > 0)
			return context.fail("leave_slope_ascending", false);
		if ((context.start.axis.y > 0 || context.end.axis.y < 0) && context.ascend < 0)
			return context.fail("leave_slope_descending", false);
		if (candidate.slopeIntersection[0] < 0 || candidate.slopeIntersection[1] < 0)
			return context.fail("too_sharp", true);
		if (candidate.turnSize < 2)
			return context.fail("too_sharp", false);
		return null;
	}

	private static PlannedPlacement validateTurnCandidate(PlanningContext context, ConnectionCandidate candidate) {
		if (candidate.absAngle < 60 || candidate.absAngle > 300)
			return context.fail("turn_90", true);
		if (candidate.turnIntersection[0] < 0 || candidate.turnIntersection[1] < 0)
			return context.fail("too_sharp", true);
		if (candidate.turnSize < candidate.minTurnSize)
			return context.fail("too_sharp", false);
		if (candidate.turnSize < candidate.turnSizeToFitAscend)
			return context.fail("too_steep", false);
		return null;
	}

	private static PlannedPlacement applyCandidate(PlanningContext context, ConnectionCandidate candidate) {
		context.info.end1Extent = candidate.end1Extent;
		context.info.end2Extent = candidate.end2Extent;
		context.info.curve = candidate.hasCurve ? createCurve(candidate, context.request) : null;
		context.info.valid = true;
		context.info.pos1 = candidate.start.pos;
		context.info.pos2 = candidate.end.pos;
		context.info.axis1 = candidate.start.axis;
		context.info.axis2 = candidate.end.axis;
		return new PlannedPlacement(context.info, candidate.start.state, candidate.end.state, candidate.targetPos1(),
			candidate.targetPos2());
	}

	private static void applyPreviewCandidate(TrackPlacement.PlacementInfo info, ConnectionCandidate candidate,
		TrackPlacementRequest request) {
		if (!request.level().isClientSide)
			return;
		info.curve = candidate.hasCurve ? createCurve(candidate, request) : null;
	}

	private static int[] symmetricCorrection(TrackPlacementRequest request, int correction) {
		if (request.maximiseTurn())
			return new int[] {0, 0};
		return new int[] {correction / 2 + (correction % 2), correction / 2};
	}

	private static double[] horizontalIntersection(TrackEndpoint start, TrackEndpoint end) {
		return VecHelper.intersect(start.end, end.end, start.normedAxis, end.normedAxis, Axis.Y);
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

	private static BezierConnection createCurve(ConnectionCandidate candidate, TrackPlacementRequest request) {
		return new BezierConnection(Couple.create(candidate.targetPos1(), candidate.targetPos2()),
			Couple.create(candidate.start.end.add(candidate.start.axis.scale(candidate.end1Extent)),
				candidate.end.end.add(candidate.end.axis.scale(candidate.end2Extent))),
			Couple.create(candidate.start.normedAxis, candidate.end.normedAxis),
			Couple.create(candidate.start.normal, candidate.end.normal), true, request.girder(), request.trackMaterial());
	}

}
