package io.github.krax1337.gsync.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The version of this app and how far an update of it has got. */
data class AppUpdate(val installed: String = "", val step: UpdateStep = UpdateStep.Idle)

sealed interface UpdateStep {
    /** Nobody has asked for an update yet. */
    data object Idle : UpdateStep

    data object Checking : UpdateStep

    data object UpToDate : UpdateStep

    /** [notes] is what the release says about itself: that version's part of the changelog. */
    data class Available(val version: String, val notes: String) : UpdateStep

    data class Downloading(val version: String, val percent: Int) : UpdateStep

    /** Downloaded and checked. Android is asking the user to confirm. */
    data class Confirming(val version: String) : UpdateStep

    data class Failed(val message: String) : UpdateStep
}

/**
 * Updates the app from the releases of its GitHub repository.
 *
 * The latest release is looked up every time the app is opened, and when the user asks. Nothing
 * is downloaded until the user asks for it. A download is handed to Android only if
 * it is this app, newer, and signed with the certificate of the installed copy. Android checks the
 * signature again and asks the user before it installs anything.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    private class Release(val version: String, val notes: String, val url: String, val size: Long)

    private class UpdateException(message: String) : Exception(message)

    private val update = MutableStateFlow(AppUpdate(installed = installedPackage().versionName.orEmpty()))
    val state: StateFlow<AppUpdate> = update

    /** The release found by the last [check]. */
    @Volatile
    private var release: Release? = null

    private var step: UpdateStep
        get() = update.value.step
        set(value) {
            update.value = update.value.copy(step = value)
        }

    /** Asks GitHub for the latest release. */
    fun check() = check(quiet = false)

    /**
     * The check made when the app is opened. It is silent: if GitHub cannot be reached nothing is
     * said about it, and the next opening tries again.
     */
    fun checkOnOpen() {
        if (step is UpdateStep.Idle || step is UpdateStep.UpToDate) check(quiet = true)
    }

    private fun check(quiet: Boolean) {
        if (step is UpdateStep.Checking || step is UpdateStep.Downloading) return
        step = UpdateStep.Checking
        scope.launch {
            val found = attempt {
                val latest = withContext(Dispatchers.IO) { latest() }
                if (isNewer(latest.version, update.value.installed)) {
                    release = latest
                    UpdateStep.Available(latest.version, latest.notes)
                } else {
                    UpdateStep.UpToDate
                }
            }
            step = if (quiet && found is UpdateStep.Failed) UpdateStep.Idle else found
        }
    }

    /** Downloads the release found by [check], checks it and hands it to Android. */
    fun install() {
        val release = release ?: return
        if (step !is UpdateStep.Available) return
        step = UpdateStep.Downloading(release.version, 0)
        scope.launch {
            step = attempt {
                withContext(Dispatchers.IO) {
                    val apk = File(context.cacheDir, "update.apk")
                    try {
                        download(release, apk)
                        verify(apk)
                        handToAndroid(apk)
                    } finally {
                        apk.delete()
                    }
                }
                UpdateStep.Confirming(release.version)
            }
        }
    }

    /** What Android made of the update once the user had answered: a `PackageInstaller` status. */
    fun finished(status: Int, message: String?) {
        step = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateStep.Idle
            // The user said no: the update is still there to take.
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                release?.let { UpdateStep.Available(it.version, it.notes) } ?: UpdateStep.Idle
            else -> UpdateStep.Failed("Android did not install the update" + if (message == null) "." else ": $message")
        }
    }

    private suspend fun attempt(work: suspend () -> UpdateStep): UpdateStep =
        try {
            work()
        } catch (e: UpdateException) {
            UpdateStep.Failed(e.message.orEmpty())
        } catch (e: IOException) {
            UpdateStep.Failed("Could not reach GitHub. Check the connection and try again.")
        } catch (e: JSONException) {
            UpdateStep.Failed("GitHub's answer could not be read.")
        }

    private fun latest(): Release {
        val json = JSONObject(open(LATEST_RELEASE) { it.inputStream.bufferedReader().use { reader -> reader.readText() } })
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        // The APK is attached a few minutes after the tag is pushed.
        val asset = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name") == "gsync-$version.apk" }
            ?: throw UpdateException("Version $version has no download yet. Try again in a few minutes.")
        val url = asset.getString("browser_download_url")
        if (!url.startsWith(DOWNLOADS)) throw UpdateException("Version $version points outside the releases of G-Sync.")
        // Release notes are a changelog section: its sub-headings read fine without their marks.
        val notes = json.optString("body").lines().joinToString("\n") { it.removePrefix("### ") }.trim()
        return Release(version, notes, url, asset.getLong("size"))
    }

    private fun download(release: Release, apk: File) {
        if (release.size !in 1..MAX_APK_BYTES) throw UpdateException("Version ${release.version} has a download of an unexpected size.")
        open(release.url) { connection ->
            connection.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        done += read
                        if (done > release.size) throw UpdateException("The download is larger than the release says.")
                        output.write(buffer, 0, read)
                        step = UpdateStep.Downloading(release.version, (done * 100 / release.size).toInt())
                    }
                    if (done != release.size) throw UpdateException("The download was cut short. Try again.")
                }
            }
        }
    }

    /** Refuses anything that is not a newer version of this app from the same signer. */
    private fun verify(apk: File) {
        val mine = installedPackage()
        val theirs = context.packageManager.getPackageArchiveInfo(apk.path, SIGNERS)
            ?: throw UpdateException("The download is not an Android app.")
        val signers = theirs.signingInfo?.apkContentsSigners?.toSet()
        when {
            theirs.packageName != context.packageName -> throw UpdateException("The download is a different app.")
            signers.isNullOrEmpty() || signers != mine.signingInfo?.apkContentsSigners?.toSet() ->
                throw UpdateException("The download is not signed with the key of this copy of G-Sync, so it was not installed.")
            theirs.longVersionCode <= mine.longVersionCode -> throw UpdateException("The download is not newer than this version.")
        }
    }

    /** Android copies the APK, then reports to [UpdateReceiver]: first that the user has to confirm. */
    private fun handToAndroid(apk: File) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("gsync.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                // Mutable, because Android adds the status to it.
                val result = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(result.intentSender)
            }
        } catch (e: IOException) {
            throw UpdateException("Android could not take the update: ${e.message}")
        }
    }

    private fun installedPackage(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, SIGNERS)

    private fun <T> open(url: String, read: (HttpURLConnection) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            connection.setRequestProperty("User-Agent", "G-Sync")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw UpdateException("GitHub answered with ${connection.responseCode}. Try again later.")
            }
            return read(connection)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val LATEST_RELEASE = "https://api.github.com/repos/krax1337/gsync/releases/latest"
        const val DOWNLOADS = "https://github.com/krax1337/gsync/releases/download/"
        const val TIMEOUT_MS = 15_000

        /** Twenty times the size of the app: anything larger is not it. */
        const val MAX_APK_BYTES = 64L * 1024 * 1024
        val SIGNERS: PackageManager.PackageInfoFlags =
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
    }
}
