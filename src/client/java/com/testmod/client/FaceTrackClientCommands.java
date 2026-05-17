package com.testmod.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.testmod.expression.ExpressionKind;
import com.testmod.expression.ExpressionParts;
import com.testmod.expression.ExpressionSnapshot;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

final class FaceTrackClientCommands {
	private static final int MAX_TRIGGER_TICKS = 20 * 60 * 60;

	private FaceTrackClientCommands() {
	}

	static void register(LocalExpressionReceiver receiver) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				literal("facetrack")
						.then(literal("status").executes(context -> status(context.getSource(), receiver)))
						.then(literal("technical_difficulties")
								.then(literal("status").executes(context -> technicalDifficultiesFaceStatus(context.getSource())))
								.then(literal("on").executes(context -> setTechnicalDifficultiesFaceEnabled(context.getSource(), true)))
								.then(literal("off").executes(context -> setTechnicalDifficultiesFaceEnabled(context.getSource(), false)))
						)
						.then(triggerCommand())
						.then(literal("hud")
								.then(literal("status").executes(context -> hudStatus(context.getSource())))
								.then(literal("on").executes(context -> setHudEnabled(context.getSource(), true)))
								.then(literal("off").executes(context -> setHudEnabled(context.getSource(), false)))
								.then(literal("corner")
										.then(argument("corner", StringArgumentType.word())
												.executes(context -> setHudCorner(
														context.getSource(),
														StringArgumentType.getString(context, "corner")
												))
										)
								)
								.then(literal("side")
										.then(argument("side", StringArgumentType.word())
												.executes(context -> setHudSide(
														context.getSource(),
														StringArgumentType.getString(context, "side")
												))
										)
								)
								.then(literal("offset")
										.then(argument("x", IntegerArgumentType.integer(-10000, 10000))
												.then(argument("y", IntegerArgumentType.integer(-10000, 10000))
														.executes(context -> setHudOffset(
																context.getSource(),
																IntegerArgumentType.getInteger(context, "x"),
																IntegerArgumentType.getInteger(context, "y")
														))
												)
										)
								)
								.then(literal("scale")
										.then(argument("scale", FloatArgumentType.floatArg(1.0F, 32.0F))
												.executes(context -> setHudScale(
														context.getSource(),
														FloatArgumentType.getFloat(context, "scale")
												))
										)
								)
						)
		));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> triggerCommand() {
		return literal("trigger")
				.then(literal("status").executes(context -> triggerStatus(context.getSource())))
				.then(literal("clear").executes(context -> clearTrigger(context.getSource())))
				.then(literal("expression")
						.then(argument("expression", StringArgumentType.word())
								.suggests((context, builder) -> suggestExpressions(builder))
								.then(argument("ticks", IntegerArgumentType.integer(1, MAX_TRIGGER_TICKS))
										.executes(context -> triggerExpression(
												context.getSource(),
												StringArgumentType.getString(context, "expression"),
												IntegerArgumentType.getInteger(context, "ticks")
										))
								)
						)
				)
				.then(literal("part")
						.then(singlePartCommand("mouth"))
						.then(singlePartCommand("left_eye"))
						.then(singlePartCommand("right_eye"))
						.then(singlePartCommand("left_eyebrow"))
						.then(singlePartCommand("right_eyebrow"))
				)
				.then(literal("parts")
						.then(argument("mouth", StringArgumentType.word())
								.suggests((context, builder) -> suggestMouths(builder))
								.then(argument("left_eye", StringArgumentType.word())
										.suggests((context, builder) -> suggestEyes(builder))
										.then(argument("right_eye", StringArgumentType.word())
												.suggests((context, builder) -> suggestEyes(builder))
												.then(argument("left_eyebrow", StringArgumentType.word())
														.suggests((context, builder) -> suggestEyebrows(builder))
														.then(argument("right_eyebrow", StringArgumentType.word())
																.suggests((context, builder) -> suggestEyebrows(builder))
																.then(argument("ticks", IntegerArgumentType.integer(1, MAX_TRIGGER_TICKS))
																		.executes(context -> triggerParts(
																				context.getSource(),
																				StringArgumentType.getString(context, "mouth"),
																				StringArgumentType.getString(context, "left_eye"),
																				StringArgumentType.getString(context, "right_eye"),
																				StringArgumentType.getString(context, "left_eyebrow"),
																				StringArgumentType.getString(context, "right_eyebrow"),
																				IntegerArgumentType.getInteger(context, "ticks")
																		))
																)
														)
												)
										)
								)
						)
				);
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> singlePartCommand(String partName) {
		return literal(partName)
				.then(argument("value", StringArgumentType.word())
						.suggests((context, builder) -> suggestPartValues(partName, builder))
						.then(argument("ticks", IntegerArgumentType.integer(1, MAX_TRIGGER_TICKS))
								.executes(context -> triggerPart(
										context.getSource(),
										partName,
										StringArgumentType.getString(context, "value"),
										IntegerArgumentType.getInteger(context, "ticks")
								))
						)
				);
	}

	private static int status(FabricClientCommandSource source, LocalExpressionReceiver receiver) {
		source.sendFeedback(Component.literal(receiver.status()));
		source.sendFeedback(Component.literal(ClientExpressionSync.serverSyncStatus()));
		source.sendFeedback(Component.literal(FaceHudConfig.technicalDifficultiesFaceStatus()));
		source.sendFeedback(Component.literal(ClientExpressionSync.triggeredExpressionStatus()));
		LocalExpressionReceiver.ReceivedExpression latest = receiver.latest();
		if (latest == null) {
			source.sendFeedback(Component.literal("Latest expression: none"));
			return 1;
		}

		ExpressionSnapshot snapshot = latest.snapshot();
		long ageMillis = Math.max(0L, System.currentTimeMillis() - latest.receivedAtMillis());
		source.sendFeedback(Component.literal(String.format(
				Locale.ROOT,
				"Latest expression: %s from %s, confidence %.2f, age %d ms",
				snapshot.expression().wireName(),
				latest.source().displayName(),
				snapshot.confidence(),
				ageMillis
		)));
		source.sendFeedback(Component.literal(String.format(
				Locale.ROOT,
				"Latest parts: mouth %s, left eye %s, right eye %s, left eyebrow %s, right eyebrow %s",
				snapshot.parts().mouth().wireName(),
				snapshot.parts().leftEye().wireName(),
				snapshot.parts().rightEye().wireName(),
				snapshot.parts().leftEyebrow().wireName(),
				snapshot.parts().rightEyebrow().wireName()
		)));
		return 1;
	}

	private static int triggerStatus(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal(ClientExpressionSync.triggeredExpressionStatus()));
		return 1;
	}

	private static int clearTrigger(FabricClientCommandSource source) {
		ClientExpressionSync.clearTriggeredExpression();
		source.sendFeedback(Component.literal(ClientExpressionSync.triggeredExpressionStatus()));
		return 1;
	}

	private static int triggerExpression(FabricClientCommandSource source, String expressionName, int ticks) {
		Optional<ExpressionKind> expression = expressionFromName(expressionName);
		if (expression.isEmpty()) {
			source.sendError(Component.literal("Unknown expression. Use " + expressionNames() + "."));
			return 0;
		}

		ClientExpressionSync.triggerExpression(expression.get(), ticks);
		source.sendFeedback(Component.literal(ClientExpressionSync.triggeredExpressionStatus()));
		return 1;
	}

	private static int triggerPart(FabricClientCommandSource source, String partName, String value, int ticks) {
		Optional<ExpressionParts> parts = partsForSinglePart(partName, value);
		if (parts.isEmpty()) {
			source.sendError(Component.literal("Unknown " + partName + " value. Use " + partValueNames(partName) + "."));
			return 0;
		}

		ClientExpressionSync.triggerParts(parts.get(), ticks);
		source.sendFeedback(Component.literal(ClientExpressionSync.triggeredExpressionStatus()));
		return 1;
	}

	private static int triggerParts(
			FabricClientCommandSource source,
			String mouthName,
			String leftEyeName,
			String rightEyeName,
			String leftEyebrowName,
			String rightEyebrowName,
			int ticks
	) {
		Optional<ExpressionParts.Mouth> mouth = mouthFromName(mouthName);
		Optional<ExpressionParts.Eye> leftEye = eyeFromName(leftEyeName);
		Optional<ExpressionParts.Eye> rightEye = eyeFromName(rightEyeName);
		Optional<ExpressionParts.Eyebrow> leftEyebrow = eyebrowFromName(leftEyebrowName);
		Optional<ExpressionParts.Eyebrow> rightEyebrow = eyebrowFromName(rightEyebrowName);
		if (mouth.isEmpty()) {
			source.sendError(Component.literal("Unknown mouth value. Use " + mouthNames() + "."));
			return 0;
		}
		if (leftEye.isEmpty() || rightEye.isEmpty()) {
			source.sendError(Component.literal("Unknown eye value. Use " + eyeNames() + "."));
			return 0;
		}
		if (leftEyebrow.isEmpty() || rightEyebrow.isEmpty()) {
			source.sendError(Component.literal("Unknown eyebrow value. Use " + eyebrowNames() + "."));
			return 0;
		}

		ClientExpressionSync.triggerParts(new ExpressionParts(
				mouth.get(),
				leftEye.get(),
				rightEye.get(),
				leftEyebrow.get(),
				rightEyebrow.get()
		), ticks);
		source.sendFeedback(Component.literal(ClientExpressionSync.triggeredExpressionStatus()));
		return 1;
	}

	private static int technicalDifficultiesFaceStatus(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal(FaceHudConfig.technicalDifficultiesFaceStatus()));
		return 1;
	}

	private static int setTechnicalDifficultiesFaceEnabled(FabricClientCommandSource source, boolean enabled) {
		FaceHudConfig.setTechnicalDifficultiesFaceEnabled(enabled);
		source.sendFeedback(Component.literal(FaceHudConfig.technicalDifficultiesFaceStatus()));
		return 1;
	}

	private static int hudStatus(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal(FaceHudConfig.status()));
		return 1;
	}

	private static int setHudEnabled(FabricClientCommandSource source, boolean enabled) {
		FaceHudConfig.setEnabled(enabled);
		source.sendFeedback(Component.literal(FaceHudConfig.status()));
		return 1;
	}

	private static int setHudCorner(FabricClientCommandSource source, String value) {
		Optional<FaceHudConfig.Corner> corner = FaceHudConfig.Corner.fromConfigValue(value);
		if (corner.isEmpty() || !isCorner(corner.get())) {
			source.sendError(Component.literal("Unknown HUD corner. Use top_left, top_right, bottom_left, or bottom_right."));
			return 0;
		}

		FaceHudConfig.setCorner(corner.get());
		source.sendFeedback(Component.literal(FaceHudConfig.status()));
		return 1;
	}

	private static int setHudSide(FabricClientCommandSource source, String value) {
		Optional<FaceHudConfig.Side> side = FaceHudConfig.Side.fromConfigValue(value);
		if (side.isEmpty()) {
			source.sendError(Component.literal("Unknown HUD side. Use left, right, top, or bottom."));
			return 0;
		}

		FaceHudConfig.setSide(side.get());
		source.sendFeedback(Component.literal(FaceHudConfig.status()));
		return 1;
	}

	private static boolean isCorner(FaceHudConfig.Corner corner) {
		return corner == FaceHudConfig.Corner.TOP_LEFT
				|| corner == FaceHudConfig.Corner.TOP_RIGHT
				|| corner == FaceHudConfig.Corner.BOTTOM_LEFT
				|| corner == FaceHudConfig.Corner.BOTTOM_RIGHT;
	}

	private static int setHudOffset(FabricClientCommandSource source, int offsetX, int offsetY) {
		FaceHudConfig.setOffset(offsetX, offsetY);
		source.sendFeedback(Component.literal(FaceHudConfig.status()));
		return 1;
	}

	private static int setHudScale(FabricClientCommandSource source, float scale) {
		FaceHudConfig.setScale(scale);
		source.sendFeedback(Component.literal(FaceHudConfig.status()));
		return 1;
	}

	private static Optional<ExpressionKind> expressionFromName(String value) {
		String normalized = normalize(value);
		for (ExpressionKind expression : ExpressionKind.values()) {
			if (expression.wireName().equals(normalized)) {
				return Optional.of(expression);
			}
		}
		return Optional.empty();
	}

	private static Optional<ExpressionParts.Mouth> mouthFromName(String value) {
		String normalized = normalize(value);
		for (ExpressionParts.Mouth mouth : ExpressionParts.Mouth.values()) {
			if (mouth.wireName().equals(normalized)) {
				return Optional.of(mouth);
			}
		}
		return Optional.empty();
	}

	private static Optional<ExpressionParts.Eye> eyeFromName(String value) {
		String normalized = normalize(value);
		for (ExpressionParts.Eye eye : ExpressionParts.Eye.values()) {
			if (eye.wireName().equals(normalized)) {
				return Optional.of(eye);
			}
		}
		return Optional.empty();
	}

	private static Optional<ExpressionParts.Eyebrow> eyebrowFromName(String value) {
		String normalized = normalize(value);
		for (ExpressionParts.Eyebrow eyebrow : ExpressionParts.Eyebrow.values()) {
			if (eyebrow.wireName().equals(normalized)) {
				return Optional.of(eyebrow);
			}
		}
		return Optional.empty();
	}

	private static Optional<ExpressionParts> partsForSinglePart(String partName, String value) {
		return switch (partName) {
			case "mouth" -> mouthFromName(value)
					.map(mouth -> new ExpressionParts(mouth, ExpressionParts.Eye.OPEN, ExpressionParts.Eye.OPEN,
							ExpressionParts.Eyebrow.NEUTRAL, ExpressionParts.Eyebrow.NEUTRAL));
			case "left_eye" -> eyeFromName(value)
					.map(eye -> new ExpressionParts(ExpressionParts.Mouth.NEUTRAL, eye, ExpressionParts.Eye.OPEN,
							ExpressionParts.Eyebrow.NEUTRAL, ExpressionParts.Eyebrow.NEUTRAL));
			case "right_eye" -> eyeFromName(value)
					.map(eye -> new ExpressionParts(ExpressionParts.Mouth.NEUTRAL, ExpressionParts.Eye.OPEN, eye,
							ExpressionParts.Eyebrow.NEUTRAL, ExpressionParts.Eyebrow.NEUTRAL));
			case "left_eyebrow" -> eyebrowFromName(value)
					.map(eyebrow -> new ExpressionParts(ExpressionParts.Mouth.NEUTRAL, ExpressionParts.Eye.OPEN, ExpressionParts.Eye.OPEN,
							eyebrow, ExpressionParts.Eyebrow.NEUTRAL));
			case "right_eyebrow" -> eyebrowFromName(value)
					.map(eyebrow -> new ExpressionParts(ExpressionParts.Mouth.NEUTRAL, ExpressionParts.Eye.OPEN, ExpressionParts.Eye.OPEN,
							ExpressionParts.Eyebrow.NEUTRAL, eyebrow));
			default -> Optional.empty();
		};
	}

	private static CompletableFuture<Suggestions> suggestExpressions(SuggestionsBuilder builder) {
		String[] names = new String[ExpressionKind.values().length];
		for (int index = 0; index < names.length; index++) {
			names[index] = ExpressionKind.values()[index].wireName();
		}
		return suggest(builder, names);
	}

	private static CompletableFuture<Suggestions> suggestMouths(SuggestionsBuilder builder) {
		String[] names = new String[ExpressionParts.Mouth.values().length];
		for (int index = 0; index < names.length; index++) {
			names[index] = ExpressionParts.Mouth.values()[index].wireName();
		}
		return suggest(builder, names);
	}

	private static CompletableFuture<Suggestions> suggestEyes(SuggestionsBuilder builder) {
		String[] names = new String[ExpressionParts.Eye.values().length];
		for (int index = 0; index < names.length; index++) {
			names[index] = ExpressionParts.Eye.values()[index].wireName();
		}
		return suggest(builder, names);
	}

	private static CompletableFuture<Suggestions> suggestEyebrows(SuggestionsBuilder builder) {
		String[] names = new String[ExpressionParts.Eyebrow.values().length];
		for (int index = 0; index < names.length; index++) {
			names[index] = ExpressionParts.Eyebrow.values()[index].wireName();
		}
		return suggest(builder, names);
	}

	private static CompletableFuture<Suggestions> suggestPartValues(String partName, SuggestionsBuilder builder) {
		return switch (partName) {
			case "mouth" -> suggestMouths(builder);
			case "left_eye", "right_eye" -> suggestEyes(builder);
			case "left_eyebrow", "right_eyebrow" -> suggestEyebrows(builder);
			default -> builder.buildFuture();
		};
	}

	private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, String... values) {
		String remaining = builder.getRemainingLowerCase();
		for (String value : values) {
			if (value.startsWith(remaining)) {
				builder.suggest(value);
			}
		}
		return builder.buildFuture();
	}

	private static String expressionNames() {
		StringJoiner joiner = new StringJoiner(", ");
		for (ExpressionKind expression : ExpressionKind.values()) {
			joiner.add(expression.wireName());
		}
		return joiner.toString();
	}

	private static String mouthNames() {
		StringJoiner joiner = new StringJoiner(", ");
		for (ExpressionParts.Mouth mouth : ExpressionParts.Mouth.values()) {
			joiner.add(mouth.wireName());
		}
		return joiner.toString();
	}

	private static String eyeNames() {
		StringJoiner joiner = new StringJoiner(", ");
		for (ExpressionParts.Eye eye : ExpressionParts.Eye.values()) {
			joiner.add(eye.wireName());
		}
		return joiner.toString();
	}

	private static String eyebrowNames() {
		StringJoiner joiner = new StringJoiner(", ");
		for (ExpressionParts.Eyebrow eyebrow : ExpressionParts.Eyebrow.values()) {
			joiner.add(eyebrow.wireName());
		}
		return joiner.toString();
	}

	private static String partValueNames(String partName) {
		return switch (partName) {
			case "mouth" -> mouthNames();
			case "left_eye", "right_eye" -> eyeNames();
			case "left_eyebrow", "right_eyebrow" -> eyebrowNames();
			default -> "";
		};
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
	}
}
