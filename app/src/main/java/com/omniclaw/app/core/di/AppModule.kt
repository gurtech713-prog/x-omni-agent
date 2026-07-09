package com.omniclaw.app.core.di

import com.omniclaw.app.data.local.AppDatabase
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
import kotlinx.serialization.json.Json
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
        val log = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .addInterceptor(log)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides @Singleton
    fun provideDatabase(provider: DatabaseProvider): AppDatabase = provider.db

    @Provides @Singleton
    fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()

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

    // UnifiedLlmClient is @Inject @Singleton — Hilt auto-provides it via its
    // constructor (LlmClient, GeminiClient, LocalLlmClient all resolve).
    // LiteRtEngine and LocalLlmClient are also @Inject @Singleton.
    // LlmClient, SttClient, VlmClient, AudioRecorder, GalleryScanner,
    // ChannelSender, BehaviorRecorder, DeepLinkManager, SuccessMonitor,
    // DeviceScheduler, and AgentLoop are all @Inject @Singleton — Hilt
    // auto-provides them via their constructors.
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
}
