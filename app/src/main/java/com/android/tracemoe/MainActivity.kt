// In file: MainActivity.kt
package com.android.tracemoe

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.android.tracemoe.ui.theme.TracemoeTheme
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import kotlin.math.roundToInt

sealed class SearchUiState {
    object Idle : SearchUiState()
    object Loading : SearchUiState()
    data class Success(
        val results: List<SearchResult>,
        val titles: Map<Long, String>
    ) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}

sealed class SearchTrigger {
    data class FromFile(val uri: Uri) : SearchTrigger()
    data class FromUrl(val url: String) : SearchTrigger()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TracemoeTheme {
                TraceMoeApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceMoeApp() {
    var uiState by remember { mutableStateOf<SearchUiState>(SearchUiState.Idle) }
    var query by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val noResultsError = stringResource(id = R.string.error_no_results)
    val cantReadFileError = stringResource(id = R.string.error_cant_read_file)
    val unknownError = stringResource(id = R.string.error_unknown)

    BackHandler(enabled = uiState !is SearchUiState.Idle) {
        uiState = SearchUiState.Idle
        query = ""
    }

    val launchSearch: (SearchTrigger) -> Unit = { trigger ->
        coroutineScope.launch {
            uiState = SearchUiState.Loading
            try {
                val response = when (trigger) {
                    is SearchTrigger.FromFile -> {
                        val imagePart = uriToMultipartBodyPart(context, trigger.uri, "image")
                        if (imagePart != null) {
                            ApiClient.traceMoeService.searchByImage(imagePart)
                        } else {
                            throw Exception(cantReadFileError)
                        }
                    }
                    is SearchTrigger.FromUrl -> {
                        ApiClient.traceMoeService.searchByUrl(trigger.url)
                    }
                }

                if (response.result.isEmpty()) {
                    uiState = SearchUiState.Error(noResultsError)
                    return@launch
                }

                val titlesMap = coroutineScope.fetchTitlesForResults(response.result)
                uiState = SearchUiState.Success(response.result, titlesMap)

            } catch (e: Exception) {
                Log.e("TraceMoeApp", "API 呼叫失败", e)
                uiState = SearchUiState.Error(e.message ?: unknownError)
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                launchSearch(SearchTrigger.FromFile(uri))
            }
        }
    )

    Surface(modifier = Modifier.fillMaxSize()) {
        if (uiState is SearchUiState.Idle) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                SearchComponent(
                    query = query,
                    onQueryChange = { query = it },
                    onImageSelect = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onUrlSearch = {
                        if (query.isNotBlank()) {
                            launchSearch(SearchTrigger.FromUrl(query))
                        }
                    }
                )
            }
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(id = R.string.search_results_title)) }
                    )
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    when (val state = uiState) {
                        is SearchUiState.Loading -> {
                            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                        }
                        is SearchUiState.Success -> {
                            SearchResultList(results = state.results, titles = state.titles)
                        }
                        is SearchUiState.Error -> {
                            Text(
                                "錯誤: ${state.message}",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }
                        is SearchUiState.Idle -> { /* This state is handled above */
                        }
                    }
                }
            }
        }
    }
}

suspend fun CoroutineScope.fetchTitlesForResults(results: List<SearchResult>): Map<Long, String> {
    val idsToFetch = results.mapNotNull { (it.anilist as? Double)?.toLong() ?: (it.anilist as? Long) }.distinct()
    if (idsToFetch.isEmpty()) return emptyMap()

    val deferredTitles = idsToFetch.map { id ->
        async(Dispatchers.IO) {
            try {
                val query =
                    """query(${'$'}id: Int) { Media(id: ${'$'}id, type: ANIME) { title { romaji native } } }""".trimIndent()
                val variables = mapOf("id" to id)
                val response = ApiClient.anilistService.getAnimeTitle(GraphQlQuery(query, variables))
                val title = response.data.Media.title
                id to (title.native ?: title.romaji ?: "Unknown")
            } catch (e: Exception) {
                Log.e("AnilistFetch", "Failed to fetch title for ID $id", e)
                id to "ID: $id (查询失败)" // This part is tricky to translate, so we leave it
            }
        }
    }
    return deferredTitles.awaitAll().toMap()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchComponent(
    query: String,
    onQueryChange: (String) -> Unit,
    onImageSelect: () -> Unit,
    onUrlSearch: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    SearchBar(
        modifier = Modifier.fillMaxWidth(),
        query = query,
        onQueryChange = onQueryChange,
        onSearch = { onUrlSearch(); keyboardController?.hide() },
        active = false,
        onActiveChange = {},
        placeholder = { Text(stringResource(id = R.string.search_placeholder)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
        trailingIcon = {
            IconButton(onClick = onImageSelect) {
                Icon(Icons.Outlined.Image, contentDescription = "Select Image")
            }
        }
    ) {}
}

@Composable
fun SearchResultList(results: List<SearchResult>, titles: Map<Long, String>) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(results) { result ->
            SearchResultItem(result = result, titles = titles)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultItem(result: SearchResult, titles: Map<Long, String>) {
    val anilistId = (result.anilist as? Double)?.toLong() ?: (result.anilist as? Long)
    val titleParseFailed = stringResource(id = R.string.error_title_parsing_failed)
    val titleText = anilistId?.let { titles[it] } ?: titleParseFailed
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedToastText = stringResource(id = R.string.toast_title_copied)

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = titleText,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = {
                        clipboardManager.setText(AnnotatedString(titleText))
                        Toast
                            .makeText(context, copiedToastText, Toast.LENGTH_SHORT)
                            .show()
                    })
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
                ) {
                    result.episode?.let {
                        Text(
                            text = stringResource(id = R.string.episode_label, it),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "${formatTime(result.from)} - ${formatTime(result.to)}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(id = R.string.similarity_label, result.similarity * 100),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AsyncImage(
                    model = result.image,
                    contentDescription = "Anime Scene",
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(16 / 9f)
                        .clip(RoundedCornerShape(bottomEnd = 12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

private fun formatTime(seconds: Double): String {
    val totalSeconds = seconds.roundToInt()
    val minutes = totalSeconds / 60
    val remainingSeconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, remainingSeconds)
}

fun uriToMultipartBodyPart(context: Context, uri: Uri, partName: String): MultipartBody.Part? {
    // ... (此函式保持不變)
    return try {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val fileBytes = inputStream?.readBytes()
        inputStream?.close()
        if (fileBytes != null) {
            val requestBody =
                fileBytes.toRequestBody(context.contentResolver.getType(uri)?.toMediaTypeOrNull())
            MultipartBody.Part.createFormData(partName, "image.jpg", requestBody)
        } else {
            null
        }
    } catch (e: Exception) {
        Log.e("Conversion", "Failed to convert URI to MultipartBody.Part", e)
        null
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    TracemoeTheme {
        TraceMoeApp()
    }
}