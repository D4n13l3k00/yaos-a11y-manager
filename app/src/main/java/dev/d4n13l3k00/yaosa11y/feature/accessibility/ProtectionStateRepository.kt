package dev.d4n13l3k00.yaosa11y.feature.accessibility

import android.content.ComponentName
import android.content.Context
import android.os.Build

data class AccessibilityProtectionSnapshot(
    val desiredEnabled: LinkedHashSet<ComponentName>,
    val protectedComponents: LinkedHashSet<ComponentName>,
    val lastKnownGood: LinkedHashSet<ComponentName>,
    val lastReconcileAt: Long,
    val lastReconcileMessage: String,
)

/**
 * The desired state is deliberately separate from Settings.Secure.
 *
 * Only confirmed user actions may update desiredEnabled. Observers and boot
 * recovery are allowed to update lastKnownGood and diagnostics, but must never
 * adopt an external YAOS reset as the new desired state.
 */
class ProtectionStateRepository(context: Context) {
    private val storageContext =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
    private val preferences =
        storageContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun isInitialized(): Boolean = preferences.getBoolean(KEY_INITIALIZED, false)

    @Synchronized
    fun initialize(
        observed: Collection<ComponentName>,
        protectObserved: Boolean,
    ) {
        if (isInitialized()) return
        val flattened = flatten(observed)
        preferences.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putStringSet(KEY_DESIRED_ENABLED, flattened)
            .putStringSet(
                KEY_PROTECTED_COMPONENTS,
                if (protectObserved) flattened else emptySet(),
            )
            .putStringSet(KEY_LAST_KNOWN_GOOD, flattened)
            .apply()
    }

    @Synchronized
    fun initializeFromLegacy(
        desired: Collection<ComponentName>,
        protected: Collection<ComponentName> = desired,
    ) {
        if (isInitialized()) return
        preferences.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putStringSet(KEY_DESIRED_ENABLED, flatten(desired))
            .putStringSet(KEY_PROTECTED_COMPONENTS, flatten(protected))
            .putStringSet(KEY_LAST_KNOWN_GOOD, flatten(desired))
            .apply()
    }

    @Synchronized
    fun recordUserState(component: ComponentName, enabled: Boolean) {
        val desired = readComponents(KEY_DESIRED_ENABLED)
        if (enabled) desired.add(component) else desired.remove(component)
        preferences.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putStringSet(KEY_DESIRED_ENABLED, flatten(desired))
            .apply()
    }

    @Synchronized
    fun setProtected(
        component: ComponentName,
        protected: Boolean,
        observedEnabled: Boolean,
    ) {
        val desired = readComponents(KEY_DESIRED_ENABLED)
        val protectedComponents = readComponents(KEY_PROTECTED_COMPONENTS)
        if (protected) {
            protectedComponents.add(component)
            if (observedEnabled) desired.add(component) else desired.remove(component)
        } else {
            protectedComponents.remove(component)
        }
        preferences.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putStringSet(KEY_DESIRED_ENABLED, flatten(desired))
            .putStringSet(KEY_PROTECTED_COMPONENTS, flatten(protectedComponents))
            .apply()
    }

    @Synchronized
    fun reconcileTarget(observed: Collection<ComponentName>): LinkedHashSet<ComponentName> {
        val snapshot = snapshot()
        val target = AccessibilityPolicy.reconcile(
            observed = observed.map(ComponentName::flattenToString),
            desiredEnabled = snapshot.desiredEnabled.mapTo(LinkedHashSet(), ComponentName::flattenToString),
            protectedComponents = snapshot.protectedComponents.mapTo(
                LinkedHashSet(),
                ComponentName::flattenToString,
            ),
        )
        return target.mapNotNullTo(LinkedHashSet(), ComponentName::unflattenFromString)
    }

    @Synchronized
    fun recordReconcile(
        state: Collection<ComponentName>,
        message: String,
    ) {
        preferences.edit()
            .putStringSet(KEY_LAST_KNOWN_GOOD, flatten(state))
            .putLong(KEY_LAST_RECONCILE_AT, System.currentTimeMillis())
            .putString(KEY_LAST_RECONCILE_MESSAGE, message)
            .apply()
    }

    @Synchronized
    fun recordFailure(message: String) {
        preferences.edit()
            .putLong(KEY_LAST_RECONCILE_AT, System.currentTimeMillis())
            .putString(KEY_LAST_RECONCILE_MESSAGE, message)
            .apply()
    }

    @Synchronized
    fun snapshot(): AccessibilityProtectionSnapshot =
        AccessibilityProtectionSnapshot(
            desiredEnabled = readComponents(KEY_DESIRED_ENABLED),
            protectedComponents = readComponents(KEY_PROTECTED_COMPONENTS),
            lastKnownGood = readComponents(KEY_LAST_KNOWN_GOOD),
            lastReconcileAt = preferences.getLong(KEY_LAST_RECONCILE_AT, 0L),
            lastReconcileMessage = preferences.getString(
                KEY_LAST_RECONCILE_MESSAGE,
                "",
            ).orEmpty(),
        )

    private fun readComponents(key: String): LinkedHashSet<ComponentName> =
        preferences.getStringSet(key, emptySet()).orEmpty()
            .mapNotNull(ComponentName::unflattenFromString)
            .sortedBy(ComponentName::flattenToString)
            .toCollection(LinkedHashSet())

    private fun flatten(components: Collection<ComponentName>): Set<String> =
        components.mapTo(LinkedHashSet(), ComponentName::flattenToString)

    companion object {
        private const val PREFERENCES = "accessibility_protection"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_DESIRED_ENABLED = "desired_enabled"
        private const val KEY_PROTECTED_COMPONENTS = "protected_components"
        private const val KEY_LAST_KNOWN_GOOD = "last_known_good"
        private const val KEY_LAST_RECONCILE_AT = "last_reconcile_at"
        private const val KEY_LAST_RECONCILE_MESSAGE = "last_reconcile_message"
    }
}
