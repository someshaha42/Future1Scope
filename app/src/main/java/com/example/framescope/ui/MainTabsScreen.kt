package com.example.framescope.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.framescope.R
import com.example.framescope.ui.theme.FrameScopeColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainTabsScreen(
    homeViewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    onOpenRecordedVideos: () -> Unit,
    onOpenCropTool: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        containerColor = FrameScopeColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Box {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = FrameScopeColors.BorderSubtle
                )
                NavigationBar(
                    containerColor = FrameScopeColors.CardSurface,
                    tonalElevation = 0.dp
                ) {
                    val homeSelected = pagerState.currentPage == 0
                    NavigationBarItem(
                        selected = homeSelected,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(0) }
                        },
                        icon = {
                            Icon(
                                imageVector = if (homeSelected) Icons.Rounded.Home else Icons.Outlined.Home,
                                contentDescription = stringResource(R.string.tab_home)
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.tab_home),
                                fontWeight = if (homeSelected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = FrameScopeColors.SelectionBlue,
                            selectedTextColor = FrameScopeColors.SelectionBlue,
                            indicatorColor = FrameScopeColors.SelectionBlueTint,
                            unselectedIconColor = FrameScopeColors.TextSecondary,
                            unselectedTextColor = FrameScopeColors.TextSecondary
                        ),
                        modifier = Modifier.testTag("nav_tab_home")
                    )

                    val toolsSelected = pagerState.currentPage == 1
                    NavigationBarItem(
                        selected = toolsSelected,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        },
                        icon = {
                            Icon(
                                imageVector = if (toolsSelected) Icons.Rounded.Build else Icons.Outlined.Build,
                                contentDescription = stringResource(R.string.tab_tools)
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.tab_tools),
                                fontWeight = if (toolsSelected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = FrameScopeColors.SelectionBlue,
                            selectedTextColor = FrameScopeColors.SelectionBlue,
                            indicatorColor = FrameScopeColors.SelectionBlueTint,
                            unselectedIconColor = FrameScopeColors.TextSecondary,
                            unselectedTextColor = FrameScopeColors.TextSecondary
                        ),
                        modifier = Modifier.testTag("nav_tab_tools")
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    viewModel = homeViewModel,
                    onOpenSettings = onOpenSettings,
                    onOpenRecordedVideos = onOpenRecordedVideos
                )
                1 -> ToolsScreen(
                    onOpenCropTool = onOpenCropTool
                )
            }
        }
    }
}
