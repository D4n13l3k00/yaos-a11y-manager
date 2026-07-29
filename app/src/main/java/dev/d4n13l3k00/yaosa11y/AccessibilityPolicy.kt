package dev.d4n13l3k00.yaosa11y

/**
 * Applies only explicitly protected states and leaves every unprotected
 * accessibility service exactly as Android currently reports it.
 */
object AccessibilityPolicy {
    fun reconcile(
        observed: Collection<String>,
        desiredEnabled: Set<String>,
        protectedComponents: Set<String>,
    ): LinkedHashSet<String> {
        val target = LinkedHashSet(observed)
        protectedComponents.forEach { component ->
            if (component in desiredEnabled) {
                target.add(component)
            } else {
                target.remove(component)
            }
        }
        return target
    }
}
