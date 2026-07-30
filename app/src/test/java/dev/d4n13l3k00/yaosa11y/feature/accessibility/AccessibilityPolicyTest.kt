package dev.d4n13l3k00.yaosa11y.feature.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityPolicyTest {
    @Test
    fun restoresProtectedEnabledServiceWithoutTouchingUnprotectedServices() {
        val target = AccessibilityPolicy.reconcile(
            observed = listOf("other/.Service"),
            desiredEnabled = setOf("protected/.Service"),
            protectedComponents = setOf("protected/.Service"),
        )

        assertEquals(
            linkedSetOf("other/.Service", "protected/.Service"),
            target,
        )
    }

    @Test
    fun disablesProtectedServiceWhoseDesiredStateIsOff() {
        val target = AccessibilityPolicy.reconcile(
            observed = listOf("protected/.Service", "other/.Service"),
            desiredEnabled = setOf("other/.Service"),
            protectedComponents = setOf("protected/.Service"),
        )

        assertEquals(linkedSetOf("other/.Service"), target)
    }

    @Test
    fun externalChangesToUnprotectedServicesArePreserved() {
        val target = AccessibilityPolicy.reconcile(
            observed = listOf("new/.Service"),
            desiredEnabled = emptySet(),
            protectedComponents = emptySet(),
        )

        assertEquals(linkedSetOf("new/.Service"), target)
    }
}
