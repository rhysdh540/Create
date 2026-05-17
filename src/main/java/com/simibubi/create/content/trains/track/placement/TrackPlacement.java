package com.simibubi.create.content.trains.track.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllSpecialTextures;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.equipment.blueprint.BlueprintOverlayRenderer;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlockItem;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackPaver;
import com.simibubi.create.content.trains.track.TrackShape;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;
import com.simibubi.create.foundation.utility.BlockHelper;
import com.simibubi.create.foundation.utility.CreateLang;
import com.simibubi.create.infrastructure.config.AllConfigs;

import io.netty.buffer.ByteBuf;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.codecs.stream.CatnipStreamCodecs;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.theme.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

public class TrackPlacement {
	public record ConnectingFrom(BlockPos pos, Vec3 axis, Vec3 normal, Vec3 end) {
		public static final Codec<ConnectingFrom> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(ConnectingFrom::pos),
			Vec3.CODEC.fieldOf("axis").forGetter(ConnectingFrom::axis),
			Vec3.CODEC.fieldOf("normal").forGetter(ConnectingFrom::normal),
			Vec3.CODEC.fieldOf("end").forGetter(ConnectingFrom::end)
		).apply(i, ConnectingFrom::new));

		public static final StreamCodec<ByteBuf, ConnectingFrom> STREAM_CODEC = StreamCodec.composite(
		    BlockPos.STREAM_CODEC, ConnectingFrom::pos,
			CatnipStreamCodecs.VEC3, ConnectingFrom::axis,
			CatnipStreamCodecs.VEC3, ConnectingFrom::normal,
			CatnipStreamCodecs.VEC3, ConnectingFrom::end,
		    ConnectingFrom::new
		);
	}

	public static class PlacementInfo {

		public PlacementInfo(TrackMaterial material) {
			this.trackMaterial = material;
		}

		BezierConnection curve = null;
		boolean valid = false;
		int end1Extent = 0;
		int end2Extent = 0;
		String message = null;

		public int requiredTracks = 0;
		public boolean hasRequiredTracks = false;

		public int requiredPavement = 0;
		public boolean hasRequiredPavement = false;
		public final TrackMaterial trackMaterial;

		// for visualisation
		Vec3 end1;
		Vec3 end2;
		Vec3 normal1;
		Vec3 normal2;
		Vec3 axis1;
		Vec3 axis2;
		BlockPos pos1;
		BlockPos pos2;

		public PlacementInfo withMessage(String message) {
			this.message = "track." + message;
			return this;
		}

			public PlacementInfo tooJumbly() {
				curve = null;
				return this;
			}

			public String getMessage() {
				return message;
			}

			public boolean isValid() {
				return valid;
			}
		}

	public static PlacementInfo cached;

	static BlockPos hoveringPos;
	static boolean hoveringMaxed;
	static int hoveringAngle;
	static ItemStack lastItem;

	static int extraTipWarmup;

	public static PlacementInfo tryConnect(Level level, Player player, BlockPos pos2, BlockState state2,
										   ItemStack stack, boolean girder, boolean maximiseTurn) {
		Vec3 lookVec = player.getLookAngle();
		int lookAngle = (int) (22.5 + AngleHelper.deg(Mth.atan2(lookVec.z, lookVec.x)) % 360) / 8;

		if (level.isClientSide && cached != null && pos2.equals(hoveringPos) && stack.equals(lastItem)
			&& hoveringMaxed == maximiseTurn && lookAngle == hoveringAngle)
			return cached;

		PlacementInfo info = new PlacementInfo(TrackMaterial.fromItem(stack.getItem()));
		hoveringMaxed = maximiseTurn;
		hoveringAngle = lookAngle;
		hoveringPos = pos2;
		lastItem = stack;
		cached = info;

		TrackPlacementRequest request = new TrackPlacementRequest(level, pos2, state2, stack, girder, maximiseTurn, lookVec);
		TrackPlacementPlanner.PlannedPlacement plan = TrackPlacementPlanner.plan(request, info);
		info = plan.info();
		if (!info.valid)
			return info;

		placeTracks(level, info, plan.state1(), plan.state2(), plan.targetPos1(), plan.targetPos2(), true);

		ItemStack offhandItem = player.getOffhandItem().copy();
		BlockItem paveItem = TrackPlacementItemRequirements.getPavingItem(offhandItem);
		boolean shouldPave = paveItem != null;
		if (shouldPave) {
			paveTracks(level, info, paveItem, true);
			info.hasRequiredPavement = true;
		}

		info.hasRequiredTracks = true;

		if (!player.isCreative())
			info = TrackPlacementItemRequirements.verifyOrConsume(level, player, stack, offhandItem, info);

		if (!info.valid)
			return info;

		if (level.isClientSide())
			return info;
		if (shouldPave)
			paveTracks(level, info, paveItem, false);
		return placeTracks(level, info, plan.state1(), plan.state2(), plan.targetPos1(), plan.targetPos2(), false);
	}

	private static void paveTracks(Level level, PlacementInfo info, BlockItem blockItem, boolean simulate) {
		Block block = blockItem.getBlock();
		info.requiredPavement = 0;
		if (block == null || block instanceof EntityBlock || block.defaultBlockState()
			.getCollisionShape(level, info.pos1)
			.isEmpty())
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

	private static PlacementInfo placeTracks(Level level, PlacementInfo info, BlockState state1, BlockState state2,
											 BlockPos targetPos1, BlockPos targetPos2, boolean simulate) {
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
				// copy over all shared properties from the shaped state to the correct track material block
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

	static LerpedFloat animation = LerpedFloat.linear()
		.startWithValue(0);
	static int lastLineCount = 0;

	static BlockPos hintPos;
	static int hintAngle;
	static Couple<List<BlockPos>> hints;

	@OnlyIn(Dist.CLIENT)
	public static void clientTick() {
		LocalPlayer player = Minecraft.getInstance().player;
		ItemStack stack = player.getMainHandItem();
		HitResult hitResult = Minecraft.getInstance().hitResult;
		int restoreWarmup = extraTipWarmup;
		extraTipWarmup = 0;

		if (hitResult == null)
			return;
		if (hitResult.getType() != Type.BLOCK)
			return;

		InteractionHand hand = InteractionHand.MAIN_HAND;
		if (!AllTags.AllBlockTags.TRACKS.matches(stack)) {
			stack = player.getOffhandItem();
			hand = InteractionHand.OFF_HAND;
			if (!AllTags.AllBlockTags.TRACKS.matches(stack))
				return;
		}

		if (!stack.hasFoil())
			return;

		TrackBlockItem blockItem = (TrackBlockItem) stack.getItem();
		Level level = player.level();
		BlockHitResult bhr = (BlockHitResult) hitResult;
		BlockPos pos = bhr.getBlockPos();
		BlockState hitState = level.getBlockState(pos);
		if (!(hitState.getBlock() instanceof TrackBlock) && !hitState.canBeReplaced()) {
			pos = pos.relative(bhr.getDirection());
			hitState = blockItem.getPlacementState(new UseOnContext(player, hand, bhr));
			if (hitState == null)
				return;
		}

		if (!(hitState.getBlock() instanceof TrackBlock))
			return;

		extraTipWarmup = restoreWarmup;
		boolean maxTurns = Minecraft.getInstance().options.keySprint.isDown();
		PlacementInfo info = tryConnect(level, player, pos, hitState, stack, false, maxTurns);
		if (extraTipWarmup < 20)
			extraTipWarmup++;
		if (!info.valid || !hoveringMaxed && (info.end1Extent == 0 || info.end2Extent == 0))
			extraTipWarmup = 0;

		if (!player.isCreative() && (info.valid || !info.hasRequiredTracks || !info.hasRequiredPavement))
			BlueprintOverlayRenderer.displayTrackRequirements(info, player.getOffhandItem());

		if (info.valid)
			player.displayClientMessage(CreateLang.translateDirect("track.valid_connection")
				.withStyle(ChatFormatting.GREEN), true);
		else if (info.message != null)
			player.displayClientMessage(CreateLang.translateDirect(info.message)
					.withStyle(info.message.equals("track.second_point") ? ChatFormatting.WHITE : ChatFormatting.RED),
				true);

		if (bhr.getDirection() == Direction.UP) {
			Vec3 lookVec = player.getLookAngle();
			int lookAngle = (int) (22.5 + AngleHelper.deg(Mth.atan2(lookVec.z, lookVec.x)) % 360) / 8;

			if (!pos.equals(hintPos) || lookAngle != hintAngle) {
				hints = Couple.create(ArrayList::new);
				hintAngle = lookAngle;
				hintPos = pos;

				for (int xOffset = -2; xOffset <= 2; xOffset++) {
					for (int zOffset = -2; zOffset <= 2; zOffset++) {
						BlockPos offset = pos.offset(xOffset, 0, zOffset);
						PlacementInfo adjInfo = tryConnect(level, player, offset, hitState, stack, false, maxTurns);
						hints.get(adjInfo.valid)
							.add(offset.below());
					}
				}
			}

			if (hints != null && !hints.either(Collection::isEmpty)) {
				Outliner.getInstance().showCluster("track_valid", hints.getFirst())
					.withFaceTexture(AllSpecialTextures.THIN_CHECKERED)
					.colored(0x95CD41)
					.lineWidth(0);
				Outliner.getInstance().showCluster("track_invalid", hints.getSecond())
					.withFaceTexture(AllSpecialTextures.THIN_CHECKERED)
					.colored(0xEA5C2B)
					.lineWidth(0);
			}
		}

		animation.chase(info.valid ? 1 : 0, 0.25, Chaser.EXP);
		animation.tickChaser();

		if (!info.valid) {
			info.end1Extent = 0;
			info.end2Extent = 0;
		}

		int color = Color.mixColors(0xEA5C2B, 0x95CD41, animation.getValue());
		Vec3 up = new Vec3(0, 4 / 16f, 0);

		{
			Vec3 v1 = info.end1;
			Vec3 a1 = info.axis1.normalize();
			Vec3 n1 = info.normal1.cross(a1)
				.scale(15 / 16f);
			Vec3 o1 = a1.scale(0.125f);
			Vec3 ex1 =
				a1.scale((info.end1Extent - (info.curve == null && info.end1Extent > 0 ? 2 : 0)) * info.axis1.length());
			line(1, v1.add(n1)
				.add(up), o1, ex1);
			line(2, v1.subtract(n1)
				.add(up), o1, ex1);

			Vec3 v2 = info.end2;
			Vec3 a2 = info.axis2.normalize();
			Vec3 n2 = info.normal2.cross(a2)
				.scale(15 / 16f);
			Vec3 o2 = a2.scale(0.125f);
			Vec3 ex2 = a2.scale(info.end2Extent * info.axis2.length());
			line(3, v2.add(n2)
				.add(up), o2, ex2);
			line(4, v2.subtract(n2)
				.add(up), o2, ex2);
		}

		BezierConnection bc = info.curve;
		if (bc == null)
			return;

		Vec3 previous1 = null;
		Vec3 previous2 = null;
		int railcolor = color;
		int segCount = bc.getSegmentCount();

		float s = animation.getValue() * 7 / 8f + 1 / 8f;
		float lw = animation.getValue() * 1 / 16f + 1 / 16f;
		Vec3 end1 = bc.starts.getFirst();
		Vec3 end2 = bc.starts.getSecond();
		Vec3 finish1 = end1.add(bc.axes.getFirst()
			.scale(bc.getHandleLength()));
		Vec3 finish2 = end2.add(bc.axes.getSecond()
			.scale(bc.getHandleLength()));
		String key = "curve";

		for (int i = 0; i <= segCount; i++) {
			float t = i / (float) segCount;
			Vec3 result = VecHelper.bezier(end1, end2, finish1, finish2, t);
			Vec3 derivative = VecHelper.bezierDerivative(end1, end2, finish1, finish2, t)
				.normalize();
			Vec3 normal = bc.getNormal(t)
				.cross(derivative)
				.scale(15 / 16f);
			Vec3 rail1 = result.add(normal)
				.add(up);
			Vec3 rail2 = result.subtract(normal)
				.add(up);

			if (previous1 != null) {
				Vec3 middle1 = rail1.add(previous1)
					.scale(0.5f);
				Vec3 middle2 = rail2.add(previous2)
					.scale(0.5f);
				Outliner.getInstance()
					.showLine(Pair.of(key, i * 2), VecHelper.lerp(s, middle1, previous1),
						VecHelper.lerp(s, middle1, rail1))
					.colored(railcolor)
					.disableLineNormals()
					.lineWidth(lw);
				Outliner.getInstance()
					.showLine(Pair.of(key, i * 2 + 1), VecHelper.lerp(s, middle2, previous2),
						VecHelper.lerp(s, middle2, rail2))
					.colored(railcolor)
					.disableLineNormals()
					.lineWidth(lw);
			}

			previous1 = rail1;
			previous2 = rail2;
		}

		for (int i = segCount + 1; i <= lastLineCount; i++) {
			Outliner.getInstance().remove(Pair.of(key, i * 2));
			Outliner.getInstance().remove(Pair.of(key, i * 2 + 1));
		}

		lastLineCount = segCount;
	}

	@OnlyIn(Dist.CLIENT)
	private static void line(int id, Vec3 v1, Vec3 o1, Vec3 ex) {
		int color = Color.mixColors(0xEA5C2B, 0x95CD41, animation.getValue());
		Outliner.getInstance().showLine(Pair.of("start", id), v1.subtract(o1), v1.add(ex))
			.lineWidth(1 / 8f)
			.disableLineNormals()
			.colored(color);
	}

}
