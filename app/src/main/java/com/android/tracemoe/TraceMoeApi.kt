// In file: TraceMoeApi.kt
package com.android.tracemoe

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MultipartBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*

// --- Trace.moe API Definitions ---

data class TraceMoeResponse(
    val frameCount: Long,
    val error: String,
    val result: List<SearchResult>
)

data class SearchResult(
    val anilist: Any,
    val filename: String,
    val episode: Int?,
    val from: Double,
    val to: Double,
    val similarity: Double,
    val video: String,
    val image: String
)

interface TraceMoeApiService {
    // 用于本地图片上传的方法 (POST)
    @Multipart
    @POST("search")
    suspend fun searchByImage(
        @Part image: MultipartBody.Part
    ): TraceMoeResponse

    // 【新增】用于 URL 搜寻的方法 (GET)
    @GET("search")
    suspend fun searchByUrl(
        @Query("url") imageUrl: String
    ): TraceMoeResponse
}


// --- AniList GraphQL API Definitions (保持不变) ---
data class GraphQlQuery(val query: String, val variables: Map<String, Any>)
data class AnilistTitleResponse(val data: AnilistTitleData)
data class AnilistTitleData(val Media: MediaTitleInfo)
data class MediaTitleInfo(val title: MediaTitle)
data class MediaTitle(val romaji: String?, val english: String?, val native: String?)
interface AnilistApiService {
    @POST("/")
    suspend fun getAnimeTitle(@Body body: GraphQlQuery): AnilistTitleResponse
}


// --- API Client Object (保持不变) ---
object ApiClient {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val traceMoeRetrofit = Retrofit.Builder()
        .baseUrl("https://api.trace.moe/")
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val anilistRetrofit = Retrofit.Builder()
        .baseUrl("https://graphql.anilist.co")
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val traceMoeService: TraceMoeApiService by lazy {
        traceMoeRetrofit.create(TraceMoeApiService::class.java)
    }

    val anilistService: AnilistApiService by lazy {
        anilistRetrofit.create(AnilistApiService::class.java)
    }
}