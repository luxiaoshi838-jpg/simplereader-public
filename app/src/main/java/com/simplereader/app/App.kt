package com.simplereader.app

import android.app.Application
import com.simplereader.app.crash.CrashLogStore
import com.simplereader.app.operation.OperationLogUiInstaller

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // v724: do not insert operation-log migration into the application startup chain.
        // Legacy v721/v722 operation storage is purged lazily by OperationLogStore before
        // the new bounded operation history is first read or written.
        CrashLogStore.install(this)
        OperationLogUiInstaller.install(this)
    }
}
