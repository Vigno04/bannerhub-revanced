package app.revanced.patches.gamehub.luatools

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.findMutableMethodOf

private const val PCENGINE_ACTIVITY =
    "Lcom/xiaoji/egggame/plugin/pcengine/host/PcEnginePluginHostActivity;"
private const val LAUNCH_HOOK =
    "Lapp/revanced/extension/gamehub/luatools/BhLuaLaunchHook;"

@Suppress("unused")
val luaToolsHooksPatch = bytecodePatch(
    name = "LuaTools & CloudRedirect launch hooks",
    description = "Hooks PcEnginePluginHostActivity to inject Online-Fix DLL overrides, " +
        "attach SteamEmu/Goldberg runtime, and trigger CloudRedirect pre-launch/post-exit sync.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, luaToolsManifestPatch)

    apply {
        firstMethod {
            definingClass == PCENGINE_ACTIVITY &&
                name == "onCreate" &&
                parameterTypes == listOf("Landroid/os/Bundle;") &&
                returnType == "V"
        }.apply {
            addInstructions(
                0,
                """
                    move-object/from16 v0, p0
                    invoke-static {v0}, $LAUNCH_HOOK->onHostActivityCreate(Landroid/app/Activity;)V
                """.trimIndent(),
            )
        }

        // Optional onDestroy hook (watchLoop in BhLuaBridgeProvider also monitors :pcengine exit)
        val pcEngineCls = classDefs.find { it.type == PCENGINE_ACTIVITY }
        val destroyMethod = pcEngineCls?.methods?.firstOrNull {
            it.name == "onDestroy" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
        if (pcEngineCls != null && destroyMethod != null) {
            classDefs.getOrReplaceMutable(pcEngineCls).findMutableMethodOf(destroyMethod).addInstructions(
                0,
                """
                    move-object/from16 v0, p0
                    invoke-static {v0}, $LAUNCH_HOOK->onHostActivityDestroy(Landroid/app/Activity;)V
                """.trimIndent(),
            )
        }
    }
}
