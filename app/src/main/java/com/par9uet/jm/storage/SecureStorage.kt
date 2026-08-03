package com.par9uet.jm.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder

class SecureStorage(
    context: Context,
    private val gson: Gson = GsonBuilder().create()
) {
    private val cryptoManager = CryptoManager()
    val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("jm-mobile-g-data", Context.MODE_PRIVATE)

    @Synchronized
    fun <T> set(key: String, t: T) {
        val json = gson.toJson(t)
        sharedPreferences.edit {
            putString(key, cryptoManager.encrypt(json))
        }
    }

    @Synchronized
    fun <T> get(key: String, type: java.lang.reflect.Type): T? {
        return try {
            getString(key)?.let {
                gson.fromJson(it, type)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @Synchronized
    fun getStringRequired(key: String): String {
        val encrypted = sharedPreferences.getString(key, null)
            ?: throw NoSuchElementException("存储数据不存在：$key")
        return cryptoManager.decrypt(encrypted)
            ?: throw IllegalStateException("存储数据无法解密：$key")
    }

    @Synchronized
    fun contains(key: String): Boolean = sharedPreferences.contains(key)

    @Synchronized
    fun getString(key: String): String? {
        val json = sharedPreferences.getString(key, null)
        return try {
            json?.let {
                cryptoManager.decrypt(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @Synchronized
    fun remove(key: String) {
        sharedPreferences.edit {
            remove(key)
        }
    }
}
