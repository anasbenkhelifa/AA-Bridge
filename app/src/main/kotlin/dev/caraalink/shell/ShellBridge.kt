package dev.caraalink.shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.IBinder
import dev.caraalink.BuildConfig
import dev.caraalink.diagnostics.DiagnosticLogger
import rikka.shizuku.Shizuku

/**
 * The app's side of Shizuku. Shizuku is a separate free app that the user
 * starts over wireless debugging; it lets apps they approve act with adb's
 * rights. With it, AA Bridge can open any app straight on the car screen and
 * touch it. Without it, nothing here does anything.
 */
object ShellBridge {
    enum class State { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST = 71

    /** The helper, once bound. Calls go across to the shell process and can throw if it has died. */
    @Volatile
    var service: IShellDisplay? = null
        private set

    private var binding = false

    /** What stands between the user and apps on the car screen. */
    internal fun stateOf(installed: Boolean, running: Boolean, granted: Boolean): State = when {
        running && granted -> State.READY
        running -> State.NEEDS_PERMISSION
        installed -> State.NOT_RUNNING
        else -> State.NOT_INSTALLED
    }

    fun state(context: Context): State {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = running && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false)
        return stateOf(installed, running, granted)
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IShellDisplay.Stub.asInterface(binder)
            binding = false
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            binding = false
        }
    }

    /** Starts the helper if Shizuku is ready and it isn't up yet. Safe to call often. */
    @Synchronized
    fun connect(context: Context) {
        if (service != null || binding || state(context) != State.READY) return
        binding = true
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellDisplayService::class.java.name))
            .daemon(false).processNameSuffix("shell").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
            binding = false
            DiagnosticLogger(context, "shell").log("bindUserService failed: $it")
        }
    }

    /** One app the car screen can open. */
    class App(val label: String, val component: String, val icon: Drawable)

    /** Every app with a launcher icon, by name. */
    fun launchableApps(context: Context): List<App> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { App(it.loadLabel(pm).toString(), "${it.activityInfo.packageName}/${it.activityInfo.name}", it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
    }
}
