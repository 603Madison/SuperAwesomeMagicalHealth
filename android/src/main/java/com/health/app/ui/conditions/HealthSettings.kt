package com.health.app.ui.conditions

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.health.app.Container
import com.health.app.HealthApp

/** The app's parts where there are any (not in previews and screenshot tests, which have none). */
@Composable
internal fun containerOrNull(): Container? = (LocalContext.current.applicationContext as? HealthApp)?.container
