package dev.opencollab.plugin

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
const val BUNDLE = "messages.OpenCollabBundle"

/**
 * UI texts. English is the default (OpenCollabBundle.properties); Spanish is in
 * OpenCollabBundle_es.properties and is used automatically when the IDE language is Spanish.
 */
object OpenCollabBundle : DynamicBundle(BUNDLE) {
    @JvmStatic
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
