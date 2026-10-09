package com.example.calltracker.di

import android.content.Context
import com.example.calltracker.data.calllog.AndroidCallLogSource
import com.example.calltracker.data.device.AndroidSimCardSource
import com.example.calltracker.data.local.AppDatabase
import com.example.calltracker.data.local.AppDatabaseFactory
import com.example.calltracker.data.local.security.DatabaseKeyProvider
import com.example.calltracker.data.phone.LibPhoneNumberNormalizer
import com.example.calltracker.data.repository.ActivityRepositoryImpl
import com.example.calltracker.data.repository.CallRepositoryImpl
import com.example.calltracker.data.repository.CrmRepositoryImpl
import com.example.calltracker.data.repository.NumberRuleRepositoryImpl
import com.example.calltracker.data.repository.OutboxRepositoryImpl
import com.example.calltracker.data.repository.RoomTransactionRunner
import com.example.calltracker.data.repository.SettingsRepositoryImpl
import com.example.calltracker.data.repository.SweepStateRepositoryImpl
import com.example.calltracker.domain.phone.PhoneNumberNormalizer
import com.example.calltracker.domain.repository.ActivityRepository
import com.example.calltracker.domain.repository.CallLogSource
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.CrmRepository
import com.example.calltracker.domain.repository.NumberRuleRepository
import com.example.calltracker.domain.repository.OutboxRepository
import com.example.calltracker.domain.repository.SettingsRepository
import com.example.calltracker.domain.repository.SimCardSource
import com.example.calltracker.data.remote.ActivityUploaderImpl
import com.example.calltracker.domain.repository.ActivityUploader
import com.example.calltracker.domain.repository.SyncRequester
import com.example.calltracker.sync.SweepRequester
import com.example.calltracker.sync.SweepScheduler
import com.example.calltracker.sync.SyncScheduler
import com.example.calltracker.domain.repository.SweepStateRepository
import com.example.calltracker.domain.repository.TransactionRunner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context, keyProvider: DatabaseKeyProvider): AppDatabase =
        AppDatabaseFactory.create(context, keyProvider)

    @Provides fun provideCallDao(db: AppDatabase) = db.callDao()

    @Provides fun provideActivityDao(db: AppDatabase) = db.activityDao()

    @Provides fun provideOutboxDao(db: AppDatabase) = db.outboxDao()

    @Provides fun provideNumberRuleDao(db: AppDatabase) = db.numberRuleDao()

    @Provides fun provideSweepStateDao(db: AppDatabase) = db.sweepStateDao()

    @Provides fun provideCrmCacheDao(db: AppDatabase) = db.crmCacheDao()

    @Provides
    @Singleton
    fun providePhoneNumberNormalizer(): PhoneNumberNormalizer = LibPhoneNumberNormalizer()

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds abstract fun bindCallRepository(impl: CallRepositoryImpl): CallRepository

    @Binds abstract fun bindActivityRepository(impl: ActivityRepositoryImpl): ActivityRepository

    @Binds abstract fun bindOutboxRepository(impl: OutboxRepositoryImpl): OutboxRepository

    @Binds abstract fun bindNumberRuleRepository(impl: NumberRuleRepositoryImpl): NumberRuleRepository

    @Binds abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner

    @Binds abstract fun bindSweepStateRepository(impl: SweepStateRepositoryImpl): SweepStateRepository

    @Binds abstract fun bindCallLogSource(impl: AndroidCallLogSource): CallLogSource

    @Binds abstract fun bindCrmRepository(impl: CrmRepositoryImpl): CrmRepository

    @Binds abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds abstract fun bindSimCardSource(impl: AndroidSimCardSource): SimCardSource

    @Binds abstract fun bindSweepRequester(impl: SweepScheduler): SweepRequester

    @Binds abstract fun bindSyncRequester(impl: SyncScheduler): SyncRequester

    @Binds abstract fun bindActivityUploader(impl: ActivityUploaderImpl): ActivityUploader
}
