package com.example.ludoduel.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ludoduel.AppContainer
import com.example.ludoduel.LudoApp

/** Creates a ViewModel that gets the [AppContainer] and the destination's [SavedStateHandle]. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): VM {
    val container = (LocalContext.current.applicationContext as LudoApp).container
    return viewModel(factory = viewModelFactory { initializer { create(container, createSavedStateHandle()) } })
}
