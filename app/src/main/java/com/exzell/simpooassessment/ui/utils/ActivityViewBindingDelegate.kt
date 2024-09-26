package com.exzell.simpooassessment.ui.utils

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.launch
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class ActivityViewBindingDelegate<T : ViewBinding>(
        val activity: ComponentActivity,
        val viewBindingFactory: (View) -> T
) : ReadOnlyProperty<Activity, T> {

    private var binding: T? = null

    init {
        activity.apply {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.DESTROYED) {
                    binding = null
                }
            }
        }
    }

    override fun getValue(thisRef: Activity, property: KProperty<*>): T {
        val binding = binding
        if(binding != null) return binding

        if(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
            throw IllegalStateException("Should not attempt to get bindings when Activity is destroyed.")
        }

        thisRef.getRootView()?.let { view ->
            return viewBindingFactory(view).also { this.binding = it }
        } ?: throw IllegalStateException("Unable to find the root view this activity belongs to")
    }

    private fun Activity.getRootView(): View? {
        return findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
    }
}

fun <T: ViewBinding> ComponentActivity.viewBinding(viewBindingFactory: (View) -> T) = ActivityViewBindingDelegate(this, viewBindingFactory)