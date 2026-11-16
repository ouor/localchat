package com.ouor.localchat.core

import javax.inject.Qualifier

/**
 * Qualifier for the process-wide CoroutineScope (SupervisorJob + Dispatchers.Default) that runs
 * chat turns and backups independently of screens (architecture 5.1). Provided by di.AppModule.
 * Lives in core because chat and backup inject it and nothing may import the app layer.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
