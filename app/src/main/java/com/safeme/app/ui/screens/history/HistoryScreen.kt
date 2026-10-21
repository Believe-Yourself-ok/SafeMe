package com.safeme.app.ui.screens.history

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.safeme.app.R
import com.safeme.app.data.ACTIVITY_A11Y
import com.safeme.app.data.ACTIVITY_BLOCK
import com.safeme.app.data.ACTIVITY_FOCUS
import com.safeme.app.data.ACTIVITY_VPN
import com.safeme.app.data.ActivityEntry
import com.safeme.app.data.activityLog
import com.safeme.app.data.formatActivityTime
import com.safeme.app.ui.screens.permissions.ChevronIcon
import com.safeme.app.ui.theme.LocalAppColors

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val _entries = kotlinx.coroutines.flow.MutableStateFlow<List<ActivityEntry>>(emptyList())
    val entries: kotlinx.coroutines.flow.StateFlow<List<ActivityEntry>> = _entries.asStateFlow()

    init {
        viewModelScope.launch {
            getApplication<Application>().activityLog().collect { _entries.value = it }
        }
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit, viewModel: HistoryViewModel = viewModel()) {
    val colors = LocalAppColors.current
    val entries by viewModel.entries.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            HistoryHeader(onBack)
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.home_feed_empty),
                        fontSize = 13.sp,
                        color = colors.ink2
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(entries, key = { "${it.timeMillis}-${it.type}-${it.title}" }) { entry ->
                        HistoryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryHeader(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)) {
        Box(
            modifier = Modifier
                .offset(x = (-8).dp)
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(14.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = ChevronIcon,
                contentDescription = stringResource(R.string.perm_back),
                modifier = Modifier.size(20.dp),
                tint = colors.ink
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_history_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            color = colors.ink
        )
    }
}

@Composable
private fun HistoryRow(entry: ActivityEntry) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(dotColor(entry.type))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink
            )
            if (entry.sub.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(text = entry.sub, fontSize = 12.sp, color = colors.ink2)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = formatActivityTime(entry.timeMillis),
                fontSize = 11.sp,
                color = colors.ink3
            )
        }
    }
}

/** Feed dot color per event kind, matching the Home feed. */
fun dotColor(type: String): Color = when (type) {
    ACTIVITY_BLOCK -> Color(0xFFD97757)
    ACTIVITY_FOCUS -> Color(0xFFC0822B)
    ACTIVITY_VPN -> Color(0xFF2E7D5B)
    ACTIVITY_A11Y -> Color(0xFFC4453C)
    else -> Color(0xFFA89E94)
}
