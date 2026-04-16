plugins {
    id("dev.kikugie.stonecutter")
    id("net.fabricmc.fabric-loom-remap") version "1.16-SNAPSHOT" apply false
    id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT" apply false
}

stonecutter active "1.21.11" /* [SC] DO NOT EDIT */

stonecutter parameters {
    val v = node.metadata.parsed

    // >= 1.21.9: Major rendering rework + method renames:
    //   - RenderType moved to rendertype subpackage, factories in RenderTypes
    //   - WorldRenderEvents/Context moved to .world subpackage
    //   - Camera: position()/yRot() (was getPosition()/getYRot())
    //   - WorldRenderContext: matrices() (was matrixStack())
    //   - NativeImage: getPointer() added
    // Targets: 1.21.4/1.21.8 = false, 1.21.11/26.1.2 = true
    constants["newRendering"] = v.matches(">=1.21.9")

    // >= 1.21.11: ResourceLocation renamed to Identifier
    constants["hasIdentifier"] = v.matches(">=1.21.11")

    // >= 1.21.11: Permissions API changed (src.permissions().hasPermission())
    constants["newPermissions"] = v.matches(">=1.21.11")

    // >= 1.21.5: DynamicTexture needs Supplier<String> + NativeImage constructor
    // Targets: 1.21.4 = false, 1.21.8/1.21.11/26.1.2 = true
    constants["newTexture"] = v.matches(">=1.21.5")

    // >= 26.0: unobfuscated MC, different loom plugin, no mappings
    constants["unobfuscated"] = v.matches(">=26.0.0")

    // >= 26.0: PayloadTypeRegistry statics renamed —
    //   playS2C/playC2S/configurationS2C/configurationC2S
    //   → clientboundPlay/serverboundPlay/clientboundConfiguration/serverboundConfiguration
    constants["newNetworkingNames"] = v.matches(">=26.0.0")
}
