/* Silverphase Android port — request-scoped async image state. GPLv3. */
package com.spectrafilm.app.silverphase

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.ensureActive

/** Read the cache now and publish an async result only for the request that produced it. */
@Composable
internal fun <K : Any, V : Any> rememberSpLoadedValue(
    request: K?,
    cached: (K) -> V?,
    load: suspend (K) -> V?,
): V? {
    var loaded by remember { mutableStateOf<SpLoadedFor<K, V>?>(null) }
    val current = loaded.valueFor(request) ?: request?.let(cached)
    LaunchedEffect(request) {
        if (request != null) {
            val value = current ?: load(request)
            ensureActive()
            if (value != null) loaded = SpLoadedFor(request, value)
        }
    }
    return current
}
