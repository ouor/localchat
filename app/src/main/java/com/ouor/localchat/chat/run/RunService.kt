package com.ouor.localchat.chat.run

import androidx.lifecycle.LifecycleService

/**
 * dataSync foreground service that keeps the process alive while any run (or backup) is active
 * and shows the "generating replies" notification. No chat logic lives here (architecture 5.6).
 */
class RunService : LifecycleService() {
    // TODO(track B): startForeground with notification, stop when RunRegistry.runs is empty, onTimeout -> stop runs.
}
