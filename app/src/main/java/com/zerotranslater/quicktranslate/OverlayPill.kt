package com.zerotranslater.quicktranslate

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zerotranslater.ui.theme.ZeroTranslaterTheme

/**
 * Hosts a [ComposeView] inside a raw `WindowManager` hierarchy.
 *
 * A `ComposeView` does not work when simply added to a window: Compose reads its
 * `ViewTreeLifecycleOwner`, `ViewTreeViewModelStoreOwner` and
 * `ViewTreeSavedStateRegistryOwner` to decide whether it is allowed to compose.
 * An activity supplies those automatically. A service has no window, so nothing
 * supplies them, and composition silently never runs. This class provides the
 * three owners and drives the lifecycle so the pill actually renders.
 */
internal class PillHost(
    private val onTap: () -> Unit,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private var composeView: ComposeView? = null

    /** Creates the view and moves the host to RESUMED so composition is permitted. */
    fun createView(context: Context): View {
        savedStateController.performRestore(null)
        savedStateController.performAttach()

        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }

        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)

        view.setContent {
            ZeroTranslaterTheme {
                PillSurface(onTap = onTap)
            }
        }

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        composeView = view
        return view
    }

    /**
     * Must be called before the view leaves the window, otherwise the composition
     * is disposed while the view is still attached and Compose throws on the next
     * frame.
     */
    fun dispose() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        // SavedStateRegistryController has no detach step (verified against
        // savedstate 1.2.1: performAttach / performRestore / performSave only).
        // The controller becomes garbage along with this host.
        store.clear()
        composeView = null
    }
}

@Composable
private fun PillSurface(onTap: () -> Unit) {
    Surface(
        onClick = onTap,
        modifier = Modifier.size(
            width = QuickTranslate.PILL_WIDTH_DP.dp,
            height = QuickTranslate.PILL_HEIGHT_DP.dp,
        ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Icon(
            imageVector = Icons.Filled.Translate,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
    }
}
