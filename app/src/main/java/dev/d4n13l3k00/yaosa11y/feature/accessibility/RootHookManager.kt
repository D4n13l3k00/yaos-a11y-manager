package dev.d4n13l3k00.yaosa11y.feature.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import dev.d4n13l3k00.yaosa11y.core.privilege.PrivilegeManager
import java.util.concurrent.Executors

/**
 * Public coordinator retained for source compatibility with the UI.
 *
 * Persistent state, Settings.Secure reconciliation, privilege selection and
 * native-hook lifecycle are implemented by separate collaborators.
 */
class RootHookManager(context: Context) {
    enum class State {
        ENABLED,
        GUARD_ONLY,
        DISABLED,
        STARTING,
        UNAVAILABLE,
    }

    data class Result(val success: Boolean, val message: String)

    data class ReconcileResult(
        val success: Boolean,
        val changed: Boolean,
        val message: String,
    )

    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val stateRepository = ProtectionStateRepository(appContext)
    private val gateway = AdbGateway()
    private val guard = AccessibilityGuard(appContext, stateRepository)
    private val privilegeManager = PrivilegeManager(appContext, gateway)
    private val nativeHook = NativeHookController(
        appContext,
        privilegeManager,
        gateway,
    )

    fun shouldBeEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun hasStoredChoice(): Boolean = preferences.contains(KEY_ENABLED)

    fun setDesiredState(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun protectionSnapshot(): AccessibilityProtectionSnapshot = stateRepository.snapshot()

    fun recordUserState(component: ComponentName, enabled: Boolean) {
        ensureStateInitialized(guard.readEnabledComponents(), protectObserved = false)
        stateRepository.recordUserState(component, enabled)
    }

    fun setServiceProtected(
        component: ComponentName,
        protected: Boolean,
        observedEnabled: Boolean,
    ) {
        ensureStateInitialized(guard.readEnabledComponents(), protectObserved = false)
        stateRepository.setProtected(component, protected, observedEnabled)
        if (protected && shouldBeEnabled()) ensureProtectionServiceRunning()
    }

    fun runAsync(enabled: Boolean, callback: (Result) -> Unit) {
        EXECUTOR.execute {
            val result = runCatching {
                if (enabled) enable() else disable()
            }.fold(
                onSuccess = { Result(true, it) },
                onFailure = { Result(false, it.message ?: it.javaClass.simpleName) },
            )
            callback(result)
        }
    }

    fun reconcileAsync(callback: (ReconcileResult) -> Unit = {}) {
        EXECUTOR.execute { callback(reconcileProtectedServices()) }
    }

    fun queryStateAsync(callback: (State) -> Unit) {
        EXECUTOR.execute { callback(queryState()) }
    }

    fun ensureProtectionServiceRunning() {
        if (!shouldBeEnabled()) return
        val intent = Intent(appContext, AccessibilityGuardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    fun enableWithRetries(attempts: Int = 8): Result {
        var lastError = "Локальный ADB пока недоступен"
        repeat(attempts) { attempt ->
            val result = runCatching(::enable)
            if (result.isSuccess) return Result(true, result.getOrThrow())
            lastError = result.exceptionOrNull()?.message ?: lastError
            if (attempt + 1 < attempts) Thread.sleep(3_000)
        }
        return Result(false, lastError)
    }

    fun queryState(): State {
        if (!shouldBeEnabled()) return State.DISABLED
        if (!guard.hasSecureSettingsPermission()) return State.UNAVAILABLE
        return when (nativeHook.queryState()) {
            NativeHookController.State.ENABLED -> State.ENABLED
            NativeHookController.State.STARTING -> State.STARTING
            NativeHookController.State.DISABLED,
            NativeHookController.State.UNAVAILABLE,
            -> State.GUARD_ONLY
        }
    }

    fun reconcileProtectedServices(): ReconcileResult {
        ensureStateInitialized(guard.readEnabledComponents(), protectObserved = true)
        val result = guard.reconcile(shouldBeEnabled())
        return ReconcileResult(result.success, result.changed, result.message)
    }

    private fun enable(): String = gateway.exclusive {
        val observed = guard.readEnabledComponents()
        ensureStateInitialized(observed, protectObserved = true)

        val permission = privilegeManager.ensureSecureSettings()
        check(permission.success) { permission.message }
        setDesiredState(true)
        ensureProtectionServiceRunning()

        val hookResult = nativeHook.install()
        val reconciled = guard.reconcile(enabled = true)
        check(reconciled.success) { reconciled.message }
        if (!hookResult.installed) {
            val message = "Guard-защита включена без native-hook: ${hookResult.message}"
            stateRepository.recordReconcile(
                stateRepository.reconcileTarget(guard.readEnabledComponents()),
                message,
            )
            return@exclusive message
        }

        check(guard.hasSecureSettingsPermission()) {
            "WRITE_SECURE_SETTINGS было потеряно после установки hook"
        }
        val target = stateRepository.reconcileTarget(guard.readEnabledComponents())
        gateway.withConnection { adb -> guard.restoreWithAdb(gateway, adb, target) }
        val message = "Guard и ${hookResult.message}"
        stateRepository.recordReconcile(target, message)
        "Защита YAOS включена: ${hookResult.message}"
    }

    private fun disable(): String = gateway.exclusive {
        val nativeMessage = nativeHook.disable()
        setDesiredState(false)
        appContext.stopService(Intent(appContext, AccessibilityGuardService::class.java))
        "Guard отключён; $nativeMessage"
    }

    private fun ensureStateInitialized(
        observed: Collection<ComponentName>,
        protectObserved: Boolean,
    ) {
        if (stateRepository.isInitialized()) return
        val legacy = preferences.getString(KEY_ACCESSIBILITY_SERVICES, null)
            ?.let(guard::parseComponents)
            .orEmpty()
        if (legacy.isNotEmpty()) {
            stateRepository.initializeFromLegacy(legacy)
        } else {
            stateRepository.initialize(observed, protectObserved)
        }
    }

    companion object {
        private const val PREFERENCES = "root_hook"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACCESSIBILITY_SERVICES = "accessibility_services"
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
