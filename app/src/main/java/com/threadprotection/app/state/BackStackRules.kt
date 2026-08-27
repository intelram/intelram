package com.threadprotection.app.state

/**
 * Back-navigation rules, extracted from [AppViewModel] so they can be unit tested: "press Back and
 * you return to where you actually came from" is easy to get subtly wrong and impossible to check
 * by reading the ViewModel alone.
 */
object BackStackRules {

    const val MAX_DEPTH = 12

    /**
     * Screens that must never be returned *to*. Going back into a finished scan would restart it,
     * and going back to sign-in after signing in would look like being signed out.
     */
    fun isBackStackable(screen: Screen): Boolean = when (screen) {
        Screen.SPLASH, Screen.SIGNIN, Screen.CREATE_ACCOUNT, Screen.ONBOARDING, Screen.SCANNING -> false
        else -> true
    }

    /**
     * The stack after navigating [from] → [to]. An earlier visit to [to] is dropped so Back walks
     * back out of a loop instead of around it, and the depth is capped so a long session can't grow
     * the stack without bound.
     */
    fun push(stack: List<Screen>, from: Screen, to: Screen, max: Int = MAX_DEPTH): List<Screen> {
        if (from == to) return stack
        if (!isBackStackable(from)) return stack
        return (stack.filter { it != to } + from).takeLast(max)
    }

    /** The screen Back should land on, or null when there is nothing to go back to. */
    fun peek(stack: List<Screen>): Screen? = stack.lastOrNull()

    /** The stack after a Back press. */
    fun pop(stack: List<Screen>): List<Screen> = stack.dropLast(1)
}
