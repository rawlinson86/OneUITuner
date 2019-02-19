package tk.zwander.oneuituner.util

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.android.apksig.ApkSigner
import eu.chainfire.libsuperuser.Shell
import tk.zwander.oneuituner.BuildConfig
import java.io.*
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.*
import javax.crypto.Cipher
import javax.crypto.CipherInputStream

enum class OverlayType {
    UNSIGNED_UNALIGNED,
    UNSIGNED,
    SIGNED
}

object Keys {
    const val systemuiPkg = "com.android.systemui"

    const val overlay = "overlay"
    const val clock = "clock"
    const val qs = "qs"

    const val clockPkg = "$systemuiPkg.${BuildConfig.APPLICATION_ID}.$overlay.$clock"
    const val qsPkg = "$systemuiPkg.${BuildConfig.APPLICATION_ID}.$overlay.$qs"
}

val Context.aapt: String?
    get() {
        val aapt = File(cacheDir, "aapt")
        if (aapt.exists()) return aapt.absolutePath

        if (!assets.extractAsset("aapt", aapt.absolutePath))
            return null

        Shell.SH.run("chmod 755 ${aapt.absolutePath}")
        return aapt.absolutePath
    }

val Context.zipalign: String?
    get() {
        val zipalign = File(cacheDir, "zipalign")
        if (zipalign.exists()) {
            Shell.SH.run("chmod 755 ${zipalign.absolutePath}")
            return zipalign.absolutePath
        }

        if (!assets.extractAsset("zipalign", zipalign.absolutePath))
            return null

        Shell.SH.run("chmod 755 ${zipalign.absolutePath}")
        return zipalign.absolutePath
    }

fun Context.install(which: String, listener: ((apk: File) -> Unit)?) {
    val data = when (which) {
        Keys.clock -> {
            OverlayInfo(
                Keys.systemuiPkg,
                Keys.clockPkg,
                arrayListOf(
                    ResourceFileData(
                        "qs_status_bar_clock.xml",
                        "layout",
                        getResourceXmlFromAsset("clock/layout", if (prefs.stockClock) "qs_status_bar_clock_aosp.xml" else "qs_status_bar_clock_tw.xml")
                            .replace("gone", prefs.amPmStyle)
                    ),
                    ResourceFileData(
                        "attrs.xml",
                        "values",
                        getResourceXmlFromAsset("clock/values", "attrs.xml")
                    )
                )
            )
        }
        Keys.qs -> {
            OverlayInfo(
                Keys.systemuiPkg,
                Keys.qsPkg,
                arrayListOf(
                    ResourceFileData(
                        "integers.xml",
                        "values",
                        makeResourceXml(
                            ResourceData(
                                "integer",
                                "quick_qs_tile_num",
                                prefs.headerCountPortrait.toString()
                            )
                        )
                    ),
                    ResourceFileData(
                        "integers.xml",
                        "values-land",
                        makeResourceXml(
                            ResourceData(
                                "integer",
                                "quick_qs_tile_num",
                                prefs.headerCountLandscape.toString()
                            )
                        )
                    )
                )
            )
            }
        else -> return
    }

    doCompileAlignAndSign(
        data,
        listener
    )
}

fun Context.getResourceXmlFromAsset(folder: String, file: String): String {
    return assets.open("$folder/$file")
        .use { stream ->
            StringBuilder()
                .apply {
                    stream.bufferedReader()
                        .forEachLine {
                            append("$it\n")
                        }
                }
                .toString()
        }
}

fun makeResourceXml(vararg data: ResourceData): String {
    return StringBuilder()
        .append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        .append("<resources xmlns:android=\"http://schemas.android.com/apk/res/android\">")
        .apply {
            data.forEach {
                append("<item type=\"${it.type}\" ${it.otherData} name=\"${it.name}\">${it.value}</item>")
            }
        }
        .append("</resources>")
        .toString()
}

fun makeLayoutXml(vararg data: LayoutData): String {
    return StringBuilder()
        .append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        .apply {
            data.forEach {
                append("<${it.tag}\n")
                it.items.forEach { t, u ->
                    append("$t=\"$u\"\n")
                }
                append(">\n")
                it.children?.forEach { child ->
                    append(makeLayoutXml(child))
                }
                append("</${it.tag}>\n")
            }
        }
        .toString()
}

fun makeOverlayFile(base: File, suffix: String, type: OverlayType): File {
    return File(base, "${suffix}_$type.apk")
}

fun makeResDir(base: File): File {
    val dir = File(base, "res/")
    if (dir.exists()) dir.deleteRecursively()

    dir.mkdirs()
    dir.mkdir()

    return dir
}

fun Context.doCompileAlignAndSign(
    overlayInfo: OverlayInfo,
    listener: ((apk: File) -> Unit)? = null
) {
    val base = makeBaseDir(overlayInfo.overlayPkg)
    val manifest = getManifest(base, overlayInfo.targetPkg, overlayInfo.overlayPkg)
    val unsignedUnaligned = makeOverlayFile(base, overlayInfo.overlayPkg, OverlayType.UNSIGNED_UNALIGNED)
    val unsigned = makeOverlayFile(base, overlayInfo.overlayPkg, OverlayType.UNSIGNED)
    val signed = makeOverlayFile(base, overlayInfo.overlayPkg, OverlayType.SIGNED)
    val resDir = makeResDir(base)

    overlayInfo.data.forEach {
        val dir = File(resDir, it.fileDirectory)

        dir.mkdirs()
        dir.mkdir()

        val resFile = File(dir, it.filename)
        if (resFile.exists()) resFile.delete()

        if (it is ResourceImageData) {
            it.image?.let {
                FileOutputStream(resFile).use { stream ->
                    it.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            }
        } else {
            OutputStreamWriter(resFile.outputStream()).use { writer ->
                writer.write(it.contents)
                writer.write("\n")
            }
        }
    }

    compileOverlay(manifest, unsignedUnaligned, resDir, overlayInfo.targetPkg)
    alignOverlay(unsignedUnaligned, unsigned)
    signOverlay(unsigned, signed)

    Shell.run("sh", arrayOf("cp ${signed.absolutePath} ${signed.absolutePath}"), null, true)

    listener?.invoke(signed)
}

fun Context.makeBaseDir(suffix: String): File {
    val dir = File(cacheDir, suffix)

    if (dir.exists()) dir.deleteRecursively()

    dir.mkdirs()
    dir.mkdir()

    Shell.SH.run("chmod 777 ${dir.absolutePath}")

    return dir
}

fun Context.getManifest(base: File, packageName: String, overlayPkg: String): File {
    val info = packageManager.getPackageInfo(packageName, 0)

    val builder = StringBuilder()
    builder.append("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"no\"?>")
    builder.append(
        "<manifest " +
                "xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "package=\"$overlayPkg\" " +
                "android:versionCode=\"100\" " +
                "android:versionName=\"100\"> "
    )
    builder.append("<uses-permission android:name=\"com.samsung.android.permission.SAMSUNG_OVERLAY_COMPONENT\" />")
    builder.append("<overlay android:targetPackage=\"$packageName\" />")
    builder.append("<application android:allowBackup=\"false\" android:hasCode=\"false\">")
    builder.append("<meta-data android:name=\"app_version\" android:value=\"v=${info.versionName}\" />")
    builder.append("<meta-data android:name=\"app_version_code\" android:value=\"v=${PackageInfoCompat.getLongVersionCode(info)}\" />")
    builder.append("<meta-data android:name=\"overlay_version\" android:value=\"100\" />")
    builder.append("<meta-data android:name=\"target_package\" android:value=\"$packageName\" />")
    builder.append("</application>")
    builder.append("</manifest>")

    val manifestFile = File(base, "AndroidManifest.xml")
    if (manifestFile.exists()) manifestFile.delete()

    OutputStreamWriter(manifestFile.outputStream()).use {
        it.write(builder.toString())
        it.write("\n")
    }

    return manifestFile
}

fun Context.compileOverlay(manifest: File, overlayFile: File, resFile: File, targetPackage: String) {
    if (overlayFile.exists()) {
        overlayFile.delete()
    }

    val aaptCmd = StringBuilder()
        .append(aapt)
        .append(" p")
        .append(" -M ")
        .append(manifest)
        .append(" -I ")
        .append("/system/framework/framework-res.apk")
        .apply {
            if (targetPackage != "android") {
                append(" -I ")
                append(packageManager.getApplicationInfo(targetPackage, 0).sourceDir)
            }
        }
        .append(" -S ")
        .append(resFile)
        .append(" -F ")
        .append(overlayFile)
        .toString()

    Log.e("OneUITuner", aaptCmd)

    Shell.run("sh", arrayOf(aaptCmd), null, true)
        .apply { Log.e("OneUITuner", toString()) }

    Shell.SH.run("chmod 777 ${overlayFile.absolutePath}")
}

fun Context.alignOverlay(overlayFile: File, alignedOverlayFile: File) {
    if (alignedOverlayFile.exists()) alignedOverlayFile.delete()

    val zipalignCmd = StringBuilder()
        .append(zipalign)
        .append(" 4 ")
        .append(overlayFile.absolutePath)
        .append(" ")
        .append(alignedOverlayFile.absolutePath)
        .toString()

    Shell.run("sh", arrayOf(zipalignCmd), null, true)

    Shell.SH.run("chmod 777 ${alignedOverlayFile.absolutePath}")
}

fun Context.signOverlay(overlayFile: File, signed: File) {
    Shell.SH.run("chmod 777 ${overlayFile.absolutePath}")

    val key = File(cacheDir, "/signing-key-new")
    val pass = "overlay".toCharArray()

    if (key.exists()) key.delete()

    val store = KeyStore.getInstance(KeyStore.getDefaultType())
    store.load(assets.open("signing-key-new"), pass)

    val privKey = store.getKey("key", pass) as PrivateKey
    val certs = ArrayList<X509Certificate>()

    certs.add(store.getCertificateChain("key")[0] as X509Certificate)

    val signConfig = ApkSigner.SignerConfig.Builder("overlay", privKey, certs).build()
    val signConfigs = ArrayList<ApkSigner.SignerConfig>()

    signConfigs.add(signConfig)

    val signer = ApkSigner.Builder(signConfigs)
    signer.setV1SigningEnabled(true)
        .setV2SigningEnabled(true)
        .setInputApk(overlayFile)
        .setOutputApk(signed)
        .setMinSdkVersion(Build.VERSION.SDK_INT)
        .build()
        .sign()

    Shell.SH.run("chmod 777 ${signed.absolutePath}")
}

fun AssetManager.extractAsset(assetPath: String, devicePath: String, cipher: Cipher?): Boolean {
    try {
        val files = list(assetPath) ?: emptyArray()
        if (files.isEmpty()) {
            return handleExtractAsset(this, assetPath, devicePath, cipher)
        }
        val f = File(devicePath)
        if (!f.exists() && !f.mkdirs()) {
            throw RuntimeException("cannot create directory: $devicePath")
        }
        var res = true
        for (file in files) {
            val assetList = list("$assetPath/$file") ?: emptyArray()
            res = if (assetList.isEmpty()) {
                res and handleExtractAsset(this, "$assetPath/$file", "$devicePath/$file", cipher)
            } else {
                res and extractAsset("$assetPath/$file", "$devicePath/$file", cipher)
            }
        }
        return res
    } catch (e: IOException) {
        e.printStackTrace()
        return false
    }
}

fun AssetManager.extractAsset(assetPath: String, devicePath: String): Boolean {
    return extractAsset(assetPath, devicePath, null)
}

private fun handleExtractAsset(
    am: AssetManager, assetPath: String, devicePath: String,
    cipher: Cipher?
): Boolean {
    var path = devicePath
    var `in`: InputStream? = null
    var out: OutputStream? = null
    val parent = File(path).parentFile
    if (!parent.exists() && !parent.mkdirs()) {
        throw RuntimeException("cannot create directory: " + parent.absolutePath)
    }

    if (path.endsWith(".enc")) {
        path = path.substring(0, path.lastIndexOf("."))
    }

    try {
        `in` = if (cipher != null && assetPath.endsWith(".enc")) {
            CipherInputStream(am.open(assetPath), cipher)
        } else {
            am.open(assetPath)
        }
        out = FileOutputStream(File(path))
        val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
        var len: Int = `in`!!.read(bytes)
        while (len != -1) {
            out.write(bytes, 0, len)
            len = `in`.read(bytes)
        }
        return true
    } catch (e: IOException) {
        e.printStackTrace()
        return false
    } finally {
        try {
            `in`?.close()
            out?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

    }
}