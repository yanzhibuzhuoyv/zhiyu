package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class CompanionPermissionPolicyTest {
    @Test fun requestsCapabilitiesSequentiallyWithoutRepeatingDenial() {
        assertEquals(CompanionPermission.CONTACT, CompanionPermissionPolicy.next(emptySet(), emptySet()))
        assertEquals(CompanionPermission.WEATHER, CompanionPermissionPolicy.next(setOf(CompanionPermission.CONTACT), emptySet()))
        assertEquals(CompanionPermission.CALENDAR, CompanionPermissionPolicy.next(
            setOf(CompanionPermission.CONTACT, CompanionPermission.WEATHER), emptySet()))
        assertNull(CompanionPermissionPolicy.next(CompanionPermission.entries.toSet(), emptySet()))
    }
    @Test fun grantedCapabilitiesDoNotTriggerRequest() {
        assertNull(CompanionPermissionPolicy.next(emptySet(), CompanionPermission.entries.toSet()))
        assertEquals(CompanionPermission.CALENDAR, CompanionPermissionPolicy.next(emptySet(),
            setOf(CompanionPermission.CONTACT, CompanionPermission.WEATHER)))
    }
    @Test fun RevocationAfterRequestDoesNotReprompt() {
        assertNull(CompanionPermissionPolicy.next(CompanionPermission.entries.toSet(), emptySet()))
    }
    @Test fun anInterruptedAttemptDoesNotMarkPermissionAsAlreadyAsked() {
        val before = setOf(CompanionPermission.CONTACT)
        val rolledBack = CompanionPermissionPolicy.afterAttempt(before + CompanionPermission.WEATHER, CompanionPermission.WEATHER, false)
        assertEquals(before, rolledBack)
        assertEquals(CompanionPermission.WEATHER, CompanionPermissionPolicy.next(rolledBack, emptySet()))
    }
    @Test fun aFailedLaunchIsBlockedOnlyForTheCurrentHostAndNeverLoops() {
        val asked = CompanionPermissionPolicy.afterAttempt(emptySet(), CompanionPermission.CONTACT, false)
        assertEquals(CompanionPermission.WEATHER, CompanionPermissionPolicy.next(asked, emptySet(), setOf(CompanionPermission.CONTACT)))
        assertEquals(CompanionPermission.CONTACT, CompanionPermissionPolicy.next(asked, emptySet()))
        assertEquals(setOf(CompanionPermission.CONTACT), CompanionPermissionPolicy.afterAttempt(asked, CompanionPermission.CONTACT, true))
    }
}
