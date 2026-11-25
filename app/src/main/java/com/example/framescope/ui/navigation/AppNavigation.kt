package com.example.framescope.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.framescope.FrameScopeApplication
import com.example.framescope.ui.EncodingSettingsScreen
import com.example.framescope.ui.HomeViewModel
import com.example.framescope.ui.MainTabsScreen
import com.example.framescope.ui.RecordedVideosScreen
import com.example.framescope.ui.VideoPlayerScreen
import com.example.framescope.ui.crop.CropToolScreen
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object EncodingSettingsRoute

@Serializable
data object RecordedVideosRoute

@Serializable
data class VideoPlayerRoute(val filePath: String)

@Serializable
data object CropToolRoute

@Composable
fun FrameScopeNavHost(application: FrameScopeApplication) {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(
            appContext = application.applicationContext,
            settingsRepository = application.settingsRepository,
            recordingsRepository = application.recordingsRepository
        )
    )

    NavHost(
        navController = navController,
        startDestination = HomeRoute
    ) {
        composable<HomeRoute> {
            MainTabsScreen(
                homeViewModel = homeViewModel,
                onOpenSettings = { navController.navigate(EncodingSettingsRoute) },
                onOpenRecordedVideos = { navController.navigate(RecordedVideosRoute) },
                onOpenCropTool = { navController.navigate(CropToolRoute) }
            )
        }

        composable<EncodingSettingsRoute> {
            EncodingSettingsScreen(
                viewModel = homeViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable<RecordedVideosRoute> {
            RecordedVideosScreen(
                viewModel = homeViewModel,
                onBack = { navController.popBackStack() },
                onPlayVideo = { filePath ->
                    navController.navigate(VideoPlayerRoute(filePath = filePath))
                }
            )
        }

        composable<VideoPlayerRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<VideoPlayerRoute>()
            VideoPlayerScreen(
                filePath = route.filePath,
                onBack = { navController.popBackStack() }
            )
        }

        composable<CropToolRoute> {
            CropToolScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
