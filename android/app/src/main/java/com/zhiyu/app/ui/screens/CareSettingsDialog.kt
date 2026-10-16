package com.zhiyu.app.ui.screens

import android.content.ActivityNotFoundException
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zhiyu.app.data.CalendarCareActions
import com.zhiyu.app.data.CompanionCalendarStore
import com.zhiyu.app.domain.CalendarDraft
import com.zhiyu.app.ui.CareController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun CareSettingsDialog(controller: CareController, close: () -> Unit) {
    val context = LocalContext.current
    val calendar = remember(context.applicationContext) { CompanionCalendarStore(context) }
    val scope = rememberCoroutineScope()
    var calendarAllowed by remember { mutableStateOf(calendar.allowed()) }
    var calendarError by remember { mutableStateOf<String?>(null) }
    var calendarNotice by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        calendarAllowed = calendar.allowed()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { calendar.setEnabled(calendarAllowed) }
                calendarError = if (calendarAllowed) null else "未允许日历权限，仍可以去系统日历手动保存。"
                calendarNotice = if (calendarAllowed) "TA 也可以写下值得记住的一刻了。" else null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                calendarError = "日历设置暂时没能保存，请稍后再试。"
            }
        }
    }
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var description by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp).testTag("care_dialog")) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("我们的日记", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭日记") }
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompanionBackgroundCard()
                    HorizontalDivider()
                    calendarError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("care_error")) }
                    calendarNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("care_notice")) }
                    Text("你可以记下特别的事，TA 也会偶尔写下自己的感受。", style = MaterialTheme.typography.bodyMedium)
                    if (!calendarAllowed) OutlinedButton(onClick = {
                        permission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                    }, modifier = Modifier.testTag("allow_companion_calendar")) { Text("允许 TA 写日记") }
                    Text("留下一件特别的事", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(title, { title = it.take(100) }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("care_calendar_title"))
                    OutlinedTextField(date, { date = it.take(10) }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("care_calendar_date"))
                    OutlinedTextField(description, { description = it.take(2000) }, label = { Text("想记下的话") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("care_calendar_description"))
                    Button(onClick = {
                        try {
                            context.startActivity(CalendarCareActions.insert(CalendarDraft(title, date, description)))
                            controller.calendarOpened()
                            calendarError = null
                            calendarNotice = "已交给系统日历，请在那里确认保存。"
                        } catch (_: ActivityNotFoundException) {
                            calendarError = "手机没有可用的日历应用。安装或启用系统日历后再试。"
                        } catch (_: SecurityException) {
                            calendarError = "系统日历暂时无法打开，请检查系统设置后再试。"
                        } catch (failure: IllegalArgumentException) {
                            calendarError = failure.message ?: "请检查日历内容"
                        }
                    }, modifier = Modifier.testTag("care_open_calendar")) { Text("去日历确认保存") }
                    Text("你写的这一条会交给系统日历确认；TA 写的日记保存在“知语 · 我们的日记”里。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
