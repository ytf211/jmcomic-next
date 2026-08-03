package com.par9uet.jm.store

import com.par9uet.jm.task.AppInitTask
import com.par9uet.jm.task.AppTaskInfo

class UserAutoLoginTask(
    private val userManager: UserManager,
) : AppInitTask {
    private val appTaskInfo = AppTaskInfo(
        taskName = "后台刷新登录状态",
        sort = 6,
    )

    override suspend fun init() {
        userManager.refreshSavedLogin()
    }

    override fun getAppTaskInfo(): AppTaskInfo = appTaskInfo
}
