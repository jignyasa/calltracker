package com.example.calltracker.data.repository

import androidx.room.withTransaction
import com.example.calltracker.data.local.AppDatabase
import com.example.calltracker.domain.repository.TransactionRunner
import javax.inject.Inject

class RoomTransactionRunner @Inject constructor(
    private val db: AppDatabase,
) : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction { block() }
}
