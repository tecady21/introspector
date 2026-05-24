package com.boyz.introspector.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.boyz.introspector.ui.screens.AppDetailScreen
import com.boyz.introspector.ui.screens.DemoScreen
import com.boyz.introspector.ui.screens.HomeScreen
import com.boyz.introspector.ui.screens.MemoryScreen
import com.boyz.introspector.ui.screens.SourceScreen
import com.boyz.introspector.ui.viewmodel.SessionViewModel

@Composable
fun NavGraph() {
    val nav = rememberNavController()
    // Created outside any composable{} block → lives at the Activity scope.
    // All screens share the same instance, so scan rounds survive navigation.
    val session: SessionViewModel = viewModel()

    NavHost(navController = nav, startDestination = "home") {

        composable("home") {
            HomeScreen(
                session = session,
                onAppClick = { packageName -> nav.navigate("detail/$packageName") },
                onDemoClick = { nav.navigate("demo") }
            )
        }

        composable("demo") {
            DemoScreen(onBack = { nav.popBackStack() })
        }

        composable(
            "detail/{packageName}",
            arguments = listOf(navArgument("packageName") { type = NavType.StringType })
        ) { entry ->
            val pkg = entry.arguments?.getString("packageName") ?: ""
            AppDetailScreen(
                packageName = pkg,
                session = session,
                onMemoryClick = { nav.navigate("memory/$pkg") },
                onSourceClick = { nav.navigate("source/$pkg") },
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            "memory/{packageName}",
            arguments = listOf(navArgument("packageName") { type = NavType.StringType })
        ) { entry ->
            val pkg = entry.arguments?.getString("packageName") ?: ""
            MemoryScreen(
                packageName = pkg,
                session = session,
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            "source/{packageName}",
            arguments = listOf(navArgument("packageName") { type = NavType.StringType })
        ) { entry ->
            val pkg = entry.arguments?.getString("packageName") ?: ""
            SourceScreen(
                packageName = pkg,
                onBack = { nav.popBackStack() }
            )
        }
    }
}
