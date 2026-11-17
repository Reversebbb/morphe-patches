/*
 * Floating player (overlay PiP) - patch definition.
 *
 * Target: a fork of MorpheApp/morphe-patches (NOT the small template repo), because this
 * patch reuses things that only exist there:
 *   - sharedExtensionPatch  (extension loading + context init)
 *   - classDefByOrNull / mutableClassDefBy  (walking YouTube's Application class)
 *   - Constants.COMPATIBILITY_YOUTUBE
 *
 * Drop this file at:
 *   patches/src/main/kotlin/app/morphe/patches/youtube/misc/floatingplayer/FloatingPlayerPatch.kt
 *
 * STATUS: not compiled. All imports and API calls below were copied from patches that
 * exist in the current morphe-patches tree, but nothing has been built or run.
 */

package app.morphe.patches.youtube.misc.floatingplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FloatingPlayerOverlay;"

private const val OVERLAY_PERMISSION = "android.permission.SYSTEM_ALERT_WINDOW"

/**
 * YouTube's Application class, declared in the manifest. Stable across versions - the
 * same constant is used by SharedExtensionPatch.
 */
private const val YOUTUBE_APPLICATION_CLASS_TYPE =
    "Lcom/google/android/apps/youtube/app/YouTubeTikTokRoot_Application;"

/**
 * Declares SYSTEM_ALERT_WINDOW. Without this, Settings.canDrawOverlays() is always false
 * and the feature can never start.
 */
private val floatingPlayerManifestPatch = patch {
    execute {
        resourceXml("AndroidManifest.xml").use { manifest ->
            val alreadyDeclared = manifest.elements("uses-permission")
                .toList()
                .any { it.getAttribute("android:name") == OVERLAY_PERMISSION }

            if (!alreadyDeclared) {
                val permission = manifest.ownerDocument.createElement("uses-permission")
                permission.setAttribute("android:name", OVERLAY_PERMISSION)
                manifest.appendChild(permission)
            }
        }
    }
}

/**
 * Adds an overlay-based floating player for devices without system PiP support.
 *
 * Not enabled by default: it needs the "Display over other apps" permission, and it moves
 * a live video surface between windows, which behaves differently on every ROM.
 */
val floatingPlayerPatch = patch(
    name = "Floating player (overlay PiP)",
    description = "Pops the playing video into a draggable floating window. For devices " +
        "whose ROM has no Android picture-in-picture support. Needs the " +
        "'Display over other apps' permission.",
    use = false,
) {
    dependsOn(
        sharedExtensionPatch,
        floatingPlayerManifestPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    /*
     * No extendWith needed: FloatingPlayerOverlay lives in the existing
     * extensions/youtube module, which sharedExtensionPatch already attaches
     * (it is passed "youtube" and "shared-youtube" as extension names).
     *
     * If the patched app crashes with NoClassDefFoundError for
     * app/morphe/extension/youtube/patches/FloatingPlayerOverlay, then that
     * assumption is wrong for your build and you add:
     *
     *   extendWith("extensions/youtube.mpe")
     *
     * here instead of creating a new extension module.
     */

    execute {
        /*
         * The feature is driven entirely from the extension: it registers
         * Application.ActivityLifecycleCallbacks and decides for itself when to pop out.
         * So the only bytecode change needed is one call that hands it a Context.
         *
         * Deliberately fingerprint-free. Nothing to re-fix after a YouTube update; the
         * cost is that the view is found at runtime (findLargestSurfaceView in the
         * extension) instead of by a known view id.
         */
        var type: String? = YOUTUBE_APPLICATION_CLASS_TYPE
        var hooked = false

        while (type != null) {
            val classDef = classDefByOrNull(type)
                ?: throw PatchException("Could not find YouTube Application class")

            val onCreate = classDef.methods.firstOrNull {
                it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V"
            }

            if (onCreate != null) {
                mutableClassDefBy(classDef).methods.first {
                    MethodUtil.methodSignaturesMatch(it, onCreate)
                }.addInstruction(
                    0,
                    "invoke-static { p0 }, $EXTENSION_CLASS->init(Landroid/content/Context;)V"
                )
                hooked = true
                break
            }

            type = classDef.superclass
        }

        if (!hooked) throw PatchException("Could not find Application.onCreate")
    }
}
