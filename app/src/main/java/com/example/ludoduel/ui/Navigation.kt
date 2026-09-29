package com.example.ludoduel.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ludoduel.ui.game.GameScreen

private object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val JOIN = "join"
    const val WAITING = "waiting/{code}"
    const val GAME = "game/{code}"
    fun waiting(code: String) = "waiting/$code"
    fun game(code: String) = "game/$code"
}

private fun NavHostController.goHome() {
    if (!popBackStack(Routes.HOME, inclusive = false)) navigate(Routes.HOME)
}

private fun NavHostController.openGame(code: String) =
    navigate(Routes.game(code)) { popUpTo(Routes.HOME) }

@Composable
fun LudoNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(onReady = { nav.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } } })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onRoomCreated = { nav.navigate(Routes.waiting(it)) },
                onJoin = { nav.navigate(Routes.JOIN) },
                onRejoin = { nav.openGame(it) },
            )
        }
        composable(Routes.JOIN) {
            JoinScreen(
                onOpenGame = { nav.openGame(it) },
                onOpenWaiting = { nav.navigate(Routes.waiting(it)) { popUpTo(Routes.HOME) } },
                onBack = { nav.goHome() },
            )
        }
        composable(Routes.WAITING) {
            WaitingScreen(onGameStarted = { nav.openGame(it) }, onClosed = { nav.goHome() })
        }
        composable(Routes.GAME) {
            GameScreen(onExit = { nav.goHome() })
        }
    }
}
