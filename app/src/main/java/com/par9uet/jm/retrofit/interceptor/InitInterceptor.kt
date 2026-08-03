package com.par9uet.jm.retrofit.interceptor

import com.par9uet.jm.retrofit.annotation.GInit
import com.par9uet.jm.store.InitManager
import com.par9uet.jm.store.StartupState
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Invocation

class InitInterceptor(
    private val initManager: InitManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val invocation = chain.request().tag(Invocation::class.java)
        val gInitAnnotation = invocation?.method()?.getAnnotation(GInit::class.java)
        if (gInitAnnotation == null) {
            val currentState = initManager.startupState.value
            val resolvedState = if (currentState is StartupState.Initializing) {
                runBlocking { initManager.awaitStartup() }
            } else {
                currentState
            }
            when (resolvedState) {
                StartupState.Ready -> Unit
                is StartupState.Failed -> throw IOException(resolvedState.message)
                StartupState.Initializing -> error("初始化等待提前结束")
            }
        }
        return chain.proceed(chain.request())
    }
}