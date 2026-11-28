package com.example.framescope.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.framescope.R
import com.example.framescope.ui.components.AeroGlassBackground
import com.example.framescope.ui.components.neumorphicShadow
import com.example.framescope.ui.theme.FrameScopeColors
import kotlinx.coroutines.launch

@Composable
fun MainTabsScreen(
    homeViewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    onOpenRecordedVideos: () -> Unit,
    onOpenCropTool: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    AeroGlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                FloatingGlassNeumorphicNavBar(
                    selectedPage = pagerState.currentPage,
                    onSelectPage = { page ->
                        coroutineScope.launch { pagerState.animateScrollToPage(page) }
                    }
                )
            }
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
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
}

@Composable
private fun FloatingGlassNeumorphicNavBar(
    selectedPage: Int,
    onSelectPage: (Int) -> Unit
) {
    val barShape = RoundedCornerShape(28.dp)
    val lightShadow = FrameScopeColors.NeuShadowLight
    val darkShadow = FrameScopeColors.NeuShadowDark

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .neumorphicShadow(
                    cornerRadius = 28.dp,
                    elevation = 7.dp,
                    lightColor = lightShadow,
                    darkColor = darkShadow
                )
                .clip(barShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            FrameScopeColors.GlassFillTop,
                            FrameScopeColors.GlassFillBottom
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            FrameScopeColors.GlassRimTop,
                            FrameScopeColors.GlassRimBottom
                        )
                    ),
                    shape = barShape
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingNavPillItem(
                label = stringResource(R.string.tab_home),
                selectedIcon = Icons.Rounded.Home,
                unselectedIcon = Icons.Outlined.Home,
                selected = selectedPage == 0,
                testTag = "nav_tab_home",
                onClick = { onSelectPage(0) },
                modifier = Modifier.weight(1f)
            )
            FloatingNavPillItem(
                label = stringResource(R.string.tab_tools),
                selectedIcon = Icons.Rounded.Build,
                unselectedIcon = Icons.Outlined.Build,
                selected = selectedPage == 1,
                testTag = "nav_tab_tools",
                onClick = { onSelectPage(1) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FloatingNavPillItem(
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(22.dp)
    val bgTop by animateColorAsState(
        targetValue = if (selected) FrameScopeColors.SelectionBlue else Color.Transparent,
        label = "nav_pill_bg_top"
    )
    val bgBottom by animateColorAsState(
        targetValue = if (selected) FrameScopeColors.SelectionBlue.copy(alpha = 0.85f) else Color.Transparent,
        label = "nav_pill_bg_bottom"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) Color.White else FrameScopeColors.TextSecondary,
        label = "nav_pill_fg"
    )

    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(pillShape)
            .background(
                brush = Brush.linearGradient(colors = listOf(bgTop, bgBottom))
            )
            .then(
                if (selected) {
                    Modifier.border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.35f),
                        shape = pillShape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp)
            .testTag(testTag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
            ),
            color = contentColor
        )
    }
}
