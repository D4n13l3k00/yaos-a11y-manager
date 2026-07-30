package dev.d4n13l3k00.yaosa11y.core.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Enables ADB through the exported CVTE factory service used by Factory Menu.
 *
 * The service runs as android.uid.system. Raw Binder transactions keep the app
 * independent from proprietary CVTE framework JARs that are absent on other
 * Android TV devices.
 */
class CvteAdbBootstrap(private val context: Context) {
    data class Result(
        val success: Boolean,
        val message: String,
    )

    fun enableThroughFactoryService(timeoutMillis: Long = BIND_TIMEOUT_MILLIS): Result {
        val latch = CountDownLatch(1)
        var result = Result(false, "Системная служба CVTE не ответила")
        var bound = false

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                Thread({
                    var lastError = "CVTE Factory API ещё не инициализирован"
                    repeat(INIT_RETRY_COUNT) { attempt ->
                        val attemptResult = runCatching {
                            val networkBinder = getNetworkBinder(service)
                                ?: error("CVTE Factory API не вернул сетевой Binder")
                            val accepted = setAdbEnabled(networkBinder)
                            Result(
                                accepted,
                                if (accepted) {
                                    "CVTE Factory API включил ADB"
                                } else {
                                    "CVTE Factory API отклонил включение ADB"
                                },
                            )
                        }
                        if (attemptResult.isSuccess) {
                            result = attemptResult.getOrThrow()
                            latch.countDown()
                            return@Thread
                        }
                        lastError = attemptResult.exceptionOrNull()?.message ?: lastError
                        if (attempt + 1 < INIT_RETRY_COUNT) Thread.sleep(INIT_RETRY_DELAY_MILLIS)
                    }
                    result = Result(false, "Ошибка CVTE Factory API: $lastError")
                    latch.countDown()
                }, "cvte-adb-bootstrap").start()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (latch.count > 0) {
                    result = Result(false, "Системная служба CVTE отключилась")
                    latch.countDown()
                }
            }
        }

        try {
            val intent = Intent(FACTORY_API_ACTION).setClassName(
                FACTORY_API_PACKAGE,
                FACTORY_API_SERVICE,
            )
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                return Result(false, "CVTE Factory API отсутствует на этом устройстве")
            }
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return Result(false, "Истекло время ожидания CVTE Factory API")
            }
            return result
        } catch (error: Exception) {
            return Result(false, "CVTE Factory API недоступен: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
        }
    }

    private fun getNetworkBinder(factoryBinder: IBinder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(FACTORY_API_DESCRIPTOR)
            check(factoryBinder.transact(TRANSACTION_GET_NETWORK, data, reply, 0)) {
                "Транзакция getFacApiNetWork не поддерживается"
            }
            reply.readException()
            reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun setAdbEnabled(networkBinder: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NETWORK_API_DESCRIPTOR)
            data.writeInt(1)
            check(networkBinder.transact(TRANSACTION_SET_ADB_STATUS, data, reply, 0)) {
                "Транзакция setAdbStatus не поддерживается"
            }
            reply.readException()
            reply.readInt() != 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        private const val FACTORY_API_PACKAGE = "com.cvte.factory.service"
        private const val FACTORY_API_SERVICE = "com.cvte.factory.service.MainService"
        private const val FACTORY_API_ACTION = "cvte.factory.intent.action.APIInit"
        private const val FACTORY_API_DESCRIPTOR = "com.cvte.factory.service.IFactoryApi"
        private const val NETWORK_API_DESCRIPTOR =
            "com.cvte.factory.service.group.IFacApiNetWork"
        private const val TRANSACTION_GET_NETWORK = 8
        private const val TRANSACTION_SET_ADB_STATUS = 37
        private const val BIND_TIMEOUT_MILLIS = 12_000L
        private const val INIT_RETRY_COUNT = 40
        private const val INIT_RETRY_DELAY_MILLIS = 250L
    }
}
