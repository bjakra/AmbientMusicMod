package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.Context
import com.kieronquinn.app.ambientmusicmod.IShellProxy
import com.kieronquinn.app.ambientmusicmod.repositories.ShizukuServiceRepository.ShizukuServiceResponse
import com.kieronquinn.app.ambientmusicmod.service.ShizukuService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


interface ShizukuServiceRepository {

    sealed class ShizukuServiceResponse<T> {
        data class Success<T>(val result: T): ShizukuServiceResponse<T>()
        data class Failed<T>(val reason: FailureReason): ShizukuServiceResponse<T>()

        enum class FailureReason {
            /**
             *  Shizuku is not bound, likely the user has not started it since rebooting
             */
            NO_BINDER,

            /**
             *  Permission to access Shizuku has not been granted
             */
            PERMISSION_DENIED,

            /**
             *  The service is not immediately available (only used in [runWithServiceIfAvailable])
             */
            NOT_AVAILABLE
        }

        /**
         *  Unwraps a result into either its value or null if it failed
         */
        fun unwrap(): T? {
            return (this as? Success)?.result
        }
    }

    val isReady: Flow<Boolean>
    suspend fun assertReady(): Boolean
    suspend fun <T> runWithService(block: (IShellProxy) -> T): ShizukuServiceResponse<T>
    fun <T> runWithServiceIfAvailable(block: (IShellProxy) -> T): ShizukuServiceResponse<T>
    fun disconnect()

}

/**
 *  Graphite: AMM ran [ShizukuService] as the shell user through Shizuku. Graphite Now Playing is a
 *  platform signed privileged system app with the permissions shell had for this, so the same
 *  service runs in this process and is always ready. Calls go through the same lock as in AMM.
 */
class ShizukuServiceRepositoryImpl(
    @Suppress("UNUSED_PARAMETER") settingsRepository: SettingsRepository,
    @Suppress("UNUSED_PARAMETER") context: Context
): ShizukuServiceRepository {

    private val service: IShellProxy by lazy { ShizukuService() }
    private val runLock = Mutex()

    override val isReady: Flow<Boolean> = flowOf(true)

    override suspend fun assertReady(): Boolean = true

    override suspend fun <T> runWithService(
        block: (IShellProxy) -> T
    ): ShizukuServiceResponse<T> = runLock.withLock {
        ShizukuServiceResponse.Success(block(service))
    }

    override fun <T> runWithServiceIfAvailable(
        block: (IShellProxy) -> T
    ): ShizukuServiceResponse<T> {
        return ShizukuServiceResponse.Success(block(service))
    }

    override fun disconnect() {
        //Nothing to unbind, the service lives in this process
    }

}
