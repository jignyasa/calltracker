package com.example.calltracker.domain.repository

/** Runs [block] atomically across repositories (Room `withTransaction` in production). */
interface TransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}
