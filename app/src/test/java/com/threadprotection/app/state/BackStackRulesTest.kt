package com.threadprotection.app.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cases for navigating between screens — Back must return where the user actually came from. */
class BackStackRulesTest {

    @Test
    fun `back returns to the screen the user came from, not the dashboard`() {
        // Dashboard → Results → Detail, then Back.
        var stack = BackStackRules.push(emptyList(), Screen.DASHBOARD, Screen.RESULTS)
        stack = BackStackRules.push(stack, Screen.RESULTS, Screen.DETAIL)
        assertEquals(Screen.RESULTS, BackStackRules.peek(stack))
        stack = BackStackRules.pop(stack)
        assertEquals(Screen.DASHBOARD, BackStackRules.peek(stack))
    }

    @Test
    fun `back from the first screen reports nothing to go back to`() {
        assertNull(BackStackRules.peek(emptyList()))
    }

    @Test
    fun `a finished scan is never returned to`() {
        assertFalse(BackStackRules.isBackStackable(Screen.SCANNING))
        // Dashboard → Scanning → Results: Back from Results must skip the scan and land on Dashboard.
        var stack = BackStackRules.push(emptyList(), Screen.DASHBOARD, Screen.SCANNING)
        stack = BackStackRules.push(stack, Screen.SCANNING, Screen.RESULTS)
        assertEquals(Screen.DASHBOARD, BackStackRules.peek(stack))
    }

    @Test
    fun `sign-in and splash are never returned to`() {
        listOf(Screen.SPLASH, Screen.SIGNIN, Screen.CREATE_ACCOUNT, Screen.ONBOARDING)
            .forEach { assertFalse("$it must not be back-stackable", BackStackRules.isBackStackable(it)) }
    }

    @Test
    fun `navigating to the screen you are already on changes nothing`() {
        val stack = listOf(Screen.DASHBOARD)
        assertEquals(stack, BackStackRules.push(stack, Screen.CHAT, Screen.CHAT))
    }

    @Test
    fun `revisiting a screen does not make back walk a loop`() {
        // Chat → History → Chat. Back should leave Chat, not bounce to History again.
        var stack = BackStackRules.push(emptyList(), Screen.DASHBOARD, Screen.CHAT)
        stack = BackStackRules.push(stack, Screen.CHAT, Screen.CHAT_HISTORY)
        stack = BackStackRules.push(stack, Screen.CHAT_HISTORY, Screen.CHAT)
        assertEquals(Screen.CHAT_HISTORY, BackStackRules.peek(stack))
        assertEquals("no earlier duplicate of CHAT is left behind", 1, stack.count { it == Screen.CHAT_HISTORY })
        assertFalse(stack.contains(Screen.CHAT))
    }

    @Test
    fun `the stack is capped so a long session cannot grow it without bound`() {
        var stack = emptyList<Screen>()
        val cycle = listOf(Screen.DASHBOARD, Screen.RESULTS, Screen.DETAIL, Screen.QR, Screen.PERMS, Screen.SETTINGS)
        var from = Screen.DASHBOARD
        repeat(60) { i ->
            val to = cycle[i % cycle.size]
            stack = BackStackRules.push(stack, from, to, max = 5)
            from = to
        }
        assertTrue(stack.size <= 5)
    }

    @Test
    fun `chat screens are all recognised as one feature`() {
        listOf(Screen.CHAT, Screen.CHAT_CONVERSATION, Screen.CHAT_HISTORY, Screen.CHAT_SESSION)
            .forEach { assertTrue("$it is part of Chat", it.isChatFeature) }
        listOf(Screen.DASHBOARD, Screen.SETTINGS, Screen.QR)
            .forEach { assertFalse("$it is not part of Chat", it.isChatFeature) }
    }

    @Test
    fun `leaving a chat screen for another chat screen keeps the feature open`() {
        // The radio is only released when the *next* screen is outside the feature — this is the
        // rule that stopped Chat tearing down its listener every time the user opened History.
        assertTrue(Screen.CHAT.isChatFeature && Screen.CHAT_HISTORY.isChatFeature)
        assertTrue(Screen.CHAT_CONVERSATION.isChatFeature && !Screen.DASHBOARD.isChatFeature)
    }
}
