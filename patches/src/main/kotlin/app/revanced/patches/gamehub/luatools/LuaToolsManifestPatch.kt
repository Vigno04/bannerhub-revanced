package app.revanced.patches.gamehub.luatools

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.all.misc.packagename.changePackageNamePatch
import app.revanced.patches.all.misc.packagename.packageNameOption
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.gog.gogManifestPatch
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.asSequence
import app.revanced.util.getNode
import org.w3c.dom.Element

private const val BRIDGE_PROVIDER =
    "app.revanced.extension.gamehub.luatools.BhLuaBridgeProvider"
private const val GAMES_ACTIVITY =
    "app.revanced.extension.gamehub.luatools.BhLuaGamesActivity"
private const val COMPANION_PKG = "com.luatools.companion"

@Suppress("unused")
val luaToolsManifestPatch = resourcePatch(
    name = "LuaTools & CloudRedirect manifest",
    description = "Registers the exported BhLuaBridgeProvider ContentProvider, LuaTools companion " +
        "package visibility query, and BhLuaGamesActivity for in-app Steam CDN downloads.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, changePackageNamePatch, gogManifestPatch)

    afterDependents {
        document("AndroidManifest.xml").use { dom ->
            val manifest = dom.getNode("manifest") as Element
            val manifestPackage = manifest.getAttribute("package")
            val variantPackage = packageNameOption.value
                ?.takeIf { it != packageNameOption.default }
                ?: manifestPackage

            val authority = "$variantPackage.bh_lua_bridge"
            val app = dom.getNode("application") as Element

            // 1. Ensure <queries><package android:name="com.luatools.companion"/></queries>
            var queriesEl = manifest.getElementsByTagName("queries").asSequence()
                .map { it as Element }
                .firstOrNull()
            if (queriesEl == null) {
                queriesEl = dom.createElement("queries")
                manifest.appendChild(queriesEl)
            }
            val hasCompanionQuery = queriesEl.getElementsByTagName("package").asSequence()
                .map { it as Element }
                .any { it.getAttribute("android:name") == COMPANION_PKG }
            if (!hasCompanionQuery) {
                val pkgEl = dom.createElement("package").apply {
                    setAttribute("android:name", COMPANION_PKG)
                }
                queriesEl.appendChild(pkgEl)
            }

            // 2. Register BhLuaBridgeProvider
            val hasProvider = app.getElementsByTagName("provider").asSequence()
                .map { it as Element }
                .any { it.getAttribute("android:name") == BRIDGE_PROVIDER }
            if (!hasProvider) {
                val providerEl = dom.createElement("provider").apply {
                    setAttribute("android:name", BRIDGE_PROVIDER)
                    setAttribute("android:authorities", authority)
                    setAttribute("android:exported", "true")
                }
                app.appendChild(providerEl)
            }

            // 3. Register BhLuaGamesActivity
            val hasActivity = app.getElementsByTagName("activity").asSequence()
                .map { it as Element }
                .any { it.getAttribute("android:name") == GAMES_ACTIVITY }
            if (!hasActivity) {
                val actEl = dom.createElement("activity").apply {
                    setAttribute("android:name", GAMES_ACTIVITY)
                    setAttribute("android:exported", "false")
                    setAttribute("android:theme", "@android:style/Theme.Black.NoTitleBar")
                    setAttribute("android:configChanges", "orientation|screenSize|keyboardHidden")
                    setAttribute("android:screenOrientation", "behind")
                }
                app.appendChild(actEl)
            }
        }
    }
}
