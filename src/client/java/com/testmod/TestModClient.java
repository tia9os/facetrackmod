package com.testmod;

import com.testmod.client.ClientExpressionSync;
import net.fabricmc.api.ClientModInitializer;

public class TestModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientExpressionSync.initialize();
	}
}
