package com.ss2ux.mp3split

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val SEGMENT_SECONDS = 30 * 60   // 30분
private const val OUTPUT_FOLDER_NAME = "분할 음원"

private val AUDIO_PERMISSION =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var hasAudioPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }
    var hasManagePerm by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
        )
    }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasAudioPerm = it
    }
    val manageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasManagePerm = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
    }

    LaunchedEffect(Unit) {
        if (!hasAudioPerm) audioLauncher.launch(AUDIO_PERMISSION)
    }

    when {
        !hasAudioPerm -> {
            PermissionScreen(
                message = "음악 파일을 읽으려면 권한이 필요해요.",
                onRequest = { audioLauncher.launch(AUDIO_PERMISSION) }
            )
        }
        !hasManagePerm -> {
            PermissionScreen(
                message = "원본 파일 위치에 '분할 음원' 폴더를 만들고 저장하려면\n모든 파일 액세스 권한이 필요해요.",
                buttonText = "권한 설정 열기",
                onRequest = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${ctx.packageName}")
                        }
                        manageLauncher.launch(intent)
                    }
                }
            )
        }
        else -> SplitScreen()
    }
}

@Composable
fun PermissionScreen(
    message: String,
    buttonText: String = "권한 허용",
    onRequest: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, fontSize = 16.sp, lineHeight = 24.sp)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) {
            Text(buttonText)
        }
    }
}

data class LongMp3(
    val path: String,          // 절대 경로
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long
)

@Composable
fun SplitScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf<List<LongMp3>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LongMp3?>(null) }
    var splitting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    fun loadFiles() {
        scope.launch {
            scanning = true
            files = withContext(Dispatchers.IO) { scanLongMp3s(ctx) }
            scanning = false
        }
    }

    LaunchedEffect(refreshTick) { loadFiles() }

    Column(modifier = Modifier.fillMaxSize()) {
        // 상단 바
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "30분 단위 음원 분할",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { refreshTick++ }, enabled = !scanning && !splitting) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                }
            }
        }

        Text(
            "Download / Music 폴더에서 30분 넘는 MP3를 찾습니다.\n분할 결과는 원본과 같은 위치의 「분할 음원」 폴더에 저장됩니다.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            lineHeight = 18.sp
        )

        HorizontalDivider()

        if (files.isEmpty() && !scanning) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MusicNote, null, Modifier.size(48.dp), tint = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    Text("30분 넘는 MP3가 없어요", color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { refreshTick++ }) {
                        Text("다시 스캔")
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(files, key = { it.path }) { item ->
                    FileCard(
                        item = item,
                        selected = selected?.path == item.path,
                        enabled = !splitting,
                        onClick = { selected = item }
                    )
                }
            }
        }

        // 하단 분할 영역
        selected?.let { sel ->
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Text(
                    sel.displayName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "길이 ${formatDuration(sel.durationMs)} · ${formatSize(sel.sizeBytes)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val parts = calcParts(sel.durationMs)

                Text(
                    "→ ${parts.size}개 파일로 분할 (${parts.joinToString(", ") { formatDuration(it * 1000L) }})",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(12.dp))

                if (splitting) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        progressText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                splitting = true
                                progress = 0f
                                progressText = "준비 중…"
                                lastResult = null
                                val result = withContext(Dispatchers.IO) {
                                    splitMp3(sel) { current, total, msg ->
                                        // UI 상태 업데이트는 메인에서
                                        kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                                            progress = current.toFloat() / total
                                            progressText = msg
                                        }
                                    }
                                }
                                splitting = false
                                lastResult = result
                                if (result.startsWith("완료")) {
                                    Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("분할 시작")
                    }
                }

                lastResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        fontSize = 13.sp,
                        color = if (it.startsWith("완료")) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }
            }
        }
    }
}

@Composable
fun FileCard(
    item: LongMp3,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 4.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AudioFile,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.Gray
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.displayName,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatDuration(item.durationMs)} · ${formatSize(item.sizeBytes)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    item.path.substringBeforeLast('/').substringAfterLast('/'),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 30분 단위 파트 초 수 리스트 (마지막은 나머지) */
fun calcParts(durationMs: Long): List<Int> {
    val totalSec = (durationMs / 1000).toInt()
    if (totalSec <= 0) return emptyList()
    val parts = mutableListOf<Int>()
    var remaining = totalSec
    while (remaining > 0) {
        val take = minOf(SEGMENT_SECONDS, remaining)
        parts.add(take)
        remaining -= take
    }
    return parts
}

fun formatDuration(ms: Long): String {
    val sec = (ms / 1000).toInt()
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "%.0f KB".format(bytes / 1000.0)
    }
}

/** Download / Music 폴더에서 30분 넘는 MP3 찾기 */
fun scanLongMp3s(ctx: Context): List<LongMp3> {
    val result = mutableListOf<LongMp3>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DATA,           // 절대 경로 (API 29에서도 개인 앱에서는 사용 가능)
        MediaStore.Audio.Media.RELATIVE_PATH
    )
    val selection = "${MediaStore.Audio.Media.DURATION} > ? AND (" +
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?)"
    val args = arrayOf(
        (SEGMENT_SECONDS * 1000L).toString(),
        "Download%",
        "Music%"
    )

    ctx.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
        val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

        while (c.moveToNext()) {
            val name = c.getString(iName) ?: continue
            if (!name.lowercase().endsWith(".mp3")) continue
            val path = c.getString(iData) ?: continue
            val dur = c.getLong(iDur)
            val size = c.getLong(iSize)
            if (dur > SEGMENT_SECONDS * 1000L && File(path).exists()) {
                result.add(LongMp3(path, name, dur, size))
            }
        }
    }
    return result.sortedByDescending { it.durationMs }
}

/**
 * 실제 분할 수행.
 * onProgress(currentPart, totalParts, message)
 * @return 성공/실패 메시지
 */
fun splitMp3(
    item: LongMp3,
    onProgress: (Int, Int, String) -> Unit
): String {
    val src = File(item.path)
    if (!src.exists()) return "오류: 원본 파일을 찾을 수 없습니다."

    val parent = src.parentFile ?: return "오류: 부모 폴더를 알 수 없습니다."
    val outDir = File(parent, OUTPUT_FOLDER_NAME)
    if (!outDir.exists() && !outDir.mkdirs()) {
        return "오류: '$OUTPUT_FOLDER_NAME' 폴더를 만들 수 없습니다. 권한을 확인하세요."
    }

    val baseName = src.nameWithoutExtension
    val parts = calcParts(item.durationMs)
    if (parts.isEmpty()) return "오류: 길이를 알 수 없습니다."

    var startSec = 0
    for ((index, lenSec) in parts.withIndex()) {
        val partNum = index + 1
        val outName = "${baseName}_${partNum}.mp3"
        val outFile = File(outDir, outName)

        onProgress(partNum - 1, parts.size, "[$partNum/${parts.size}] $outName 생성 중…")

        // -ss 를 -i 앞에 두면 빠른 탐색, -c copy 로 재인코딩 없이 자르기
        val cmd = buildString {
            append("-y ")
            append("-ss $startSec ")
            append("-i \"${src.absolutePath}\" ")
            append("-t $lenSec ")
            append("-c copy ")
            append("-map_metadata 0 ")
            append("\"${outFile.absolutePath}\"")
        }

        val session = FFmpegKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val log = session.allLogsAsString.takeLast(300)
            return "오류 (파트 $partNum): FFmpeg 실패\n$log"
        }
        startSec += lenSec
        onProgress(partNum, parts.size, "[$partNum/${parts.size}] $outName 완료")
    }

    return "완료! ${parts.size}개 파일이\n${outDir.absolutePath}\n에 저장되었습니다."
}
