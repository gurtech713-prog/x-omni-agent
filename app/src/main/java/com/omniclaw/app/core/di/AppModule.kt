package com.omniclaw.app.core.di

import com.omniclaw.app.data.local.AppDatabase
import com.omniclaw.app.data.local.ChatMessageDao
import com.omniclaw.app.data.local.DatabaseProvider
import com.omniclaw.app.data.local.LessonDao
import com.omniclaw.app.data.local.LiteRtEngine
import com.omniclaw.app.data.local.LocalLlmClient
import com.omniclaw.app.data.local.MemoryDao
import com.omniclaw.app.data.local.SessionDao
import com.omniclaw.app.data.llm.GeminiClient
import com.omniclaw.app.data.llm.UnifiedLlmClient
import com.omniclaw.app.data.memory.MemoryRepository
import com.omniclaw.app.data.memory.MemoryRepositoryImpl
import com.omniclaw.app.data.prefs.SettingsRepository
import com.omniclaw.app.data.prefs.SettingsRepositoryImpl
import com.omniclaw.app.data.session.SessionRepository
import com.omniclaw.app.data.session.SessionRepositoryImpl
import com.omniclaw.app.data.skill.SkillRepository
import com.omniclaw.app.data.skill.SkillRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient {
        // PERFORMANCE: read timeout 60s (was 30s). The agent loop's step timeout
        // is 45s — if OkHttp fires first, the LLM call fails prematurely and the
        // retry wastes another 45s. By setting read timeout >= 60s, the step
        // timeout always wins, giving the LLM the full budget.
        // HttpLoggingInterceptor at BASIC level would log 2 lines per call
        // (POST + 200 OK), adding ~0.1ms overhead. Removed since logcat is noisy.
        return OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides @Singleton
    fun provideDatabase(provider: DatabaseProvider): AppDatabase = provider.db

    @Provides @Singleton
    fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()

    @Provides @Singleton
    fun provideChatMessageDao(db: AppDatabase): ChatMessageDao = db.chatMessageDao()

    @Provides @Singleton
    fun provideMemoryDao(db: AppDatabase): MemoryDao = db.memoryDao()

    @Provides @Singleton
    fun provideLessonDao(db: AppDatabase): LessonDao = db.lessonDao()

    // GeminiClient is constructed manually (no @Inject on its constructor)
    // because it doesn't need a SettingsRepository — callers pass baseUrl /
    // apiKey per-request. Keeping it stateless makes it easy to test.
    @Provides @Singleton
    fun provideGeminiClient(http: OkHttpClient, json: Json): GeminiClient =
        GeminiClient(http, json)

    // ── Dispatchers ───────────────────────────────────────────────────────
    // Provided as qualified CoroutineDispatchers so any class can inject the
    // exact dispatcher it needs via @IoDispatcher / @DefaultDispatcher etc.
    // Tests override these with a StandardTestDispatcher to control timing.
    @Provides @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides @MainImmediateDispatcher
    fun provideMainImmediateDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    // ── Accessibility subsystem ────────────────────────────────────────────
    // Diagnostics, metrics, window tracker, and executor are all @Singleton
    // so the agent loop, the a11y service, and the diagnostics UI all share
    // one instance. AccessibilityExecutor is @Inject @Singleton (auto-provided
    // via its constructor); the others are also @Inject @Singleton.
    @Provides @Singleton
    fun provideNodeSearchEngine(): com.omniclaw.app.accessibility.NodeSearchEngine =
        com.omniclaw.app.accessibility.NodeSearchEngine()



    // UnifiedLlmClient is @Inject @Singleton — Hilt auto-provides it via its
    // constructor (LlmClient, GeminiClient, LocalLlmClient all resolve).
    // LiteRtEngine and LocalLlmClient are also @Inject @Singleton.
    // LlmClient, SttClient, VlmClient, AudioRecorder, GalleryScanner,
    // ChannelSender, BehaviorRecorder, DeepLinkManager, SuccessMonitor,
    // DeviceScheduler, AgentLoop, and TextToSpeechManager are all
    // @Inject @Singleton — Hilt auto-provides them via their constructors.
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindSettings(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindSessions(impl: SessionRepositoryImpl): SessionRepository

    @Binds @Singleton
    abstract fun bindSkills(impl: SkillRepositoryImpl): SkillRepository

    @Binds @Singleton
    abstract fun bindMemory(impl: MemoryRepositoryImpl): MemoryRepository

    @Binds @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider
}
