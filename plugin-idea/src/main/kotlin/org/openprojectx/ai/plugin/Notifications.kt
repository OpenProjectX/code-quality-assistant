package org.openprojectx.ai.plugin

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

object Notifications {

    private const val GROUP_ID = "OpenProjectX Notifications"

    fun info(project: Project?, title: String, message: String) {
        RuntimeLogStore.append("INFO | $title | $message")
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, message, NotificationType.INFORMATION)
            .notify(project)
    }

    fun warn(project: Project?, title: String, message: String) {
        RuntimeLogStore.append("WARN | $title | $message")
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, message, NotificationType.WARNING)
            .notify(project)
    }

    fun error(project: Project?, title: String, message: String) {
        RuntimeLogStore.append("ERROR | $title | $message")
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, message, NotificationType.ERROR)
            .notify(project)
    }

    fun notifyFileGenerated(project: Project, title: String, message: String, file: VirtualFile) {

        val notification =
            NotificationGroupManager.getInstance()
                .getNotificationGroup("OpenProjectX Notifications")
                .createNotification(
                    title,
                    message,
                    NotificationType.INFORMATION
                )

        notification.addAction(
            NotificationAction.createSimple("Open File") {
                FileEditorManager.getInstance(project).openFile(file, true)
            }
        )

        notification.notify(project)
    }
}
