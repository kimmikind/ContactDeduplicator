package com.example.contactdeduplicator

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.contactdeduplicator.ui.theme.ContactDeduplicatorTheme
import android.Manifest



class MainActivity : ComponentActivity() {
    companion object {
        // Код запроса разрешений
        private const val PERMISSION_REQUEST_CODE = 100
        // Необходимые разрешения
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        )
    }

    // Ссылка на AIDL-интерфейс сервиса
    private var service: IDeduplicator? = null

    // Состояния UI
    private var isBound by mutableStateOf(false)
    private var isLoading by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Сервис не подключен")

    // запрос разрешений
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            initApp()
        } else {
            Toast.makeText(this, "Разрешения необходимы", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Проверяем есть ли все разрешения
    private fun checkPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Запрашиваем разрешения
    private fun requestPermissions() {
        requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    // Инициализация UI после получения разрешений
    private fun initApp() {
        setContent {
            ContactDeduplicatorTheme {
                Surface {
                    ContactDeduplicatorScreen(
                        isBound = isBound,
                        isLoading = isLoading,
                        statusMessage = statusMessage,
                        onCleanClick = ::cleanContacts
                    )
                }
            }
        }
    }

    // Подключение к сервису
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            service = IDeduplicator.Stub.asInterface(binder)
            isBound = true
            statusMessage = "Сервис подключен"
        }

        override fun onServiceDisconnected(className: ComponentName) {
            service = null
            isBound = false
            statusMessage = "Сервис отключен"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkPermissions()) initApp() else requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DeduplicateContactService::class.java),
            connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    // Запуск очистки дубликатов
    private fun cleanContacts() {
        if (!isBound) return

        isLoading = true
        statusMessage = "Поиск дубликатов..."

        // Запускаем в фоновом потоке
        Thread {
            try {
                val result = service?.deleteDuplContacts()
                runOnUiThread {
                    isLoading = false
                    statusMessage = when (result) {
                        DeduplicateContactService.SUCCESS -> "Дубликаты удалены"
                        DeduplicateContactService.ERROR_OCCURRED -> "Ошибка"
                        DeduplicateContactService.NO_DUPLICATES_FOUND -> "Дубликатов нет"
                        else -> "Неизвестный результат"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isLoading = false
                    statusMessage = "Ошибка: ${e.localizedMessage}"
                }
            }
        }.start()
    }
}


@Composable
fun ContactDeduplicatorScreen(
    isBound: Boolean,
    isLoading: Boolean,
    statusMessage: String,
    onCleanClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = statusMessage,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = onCleanClick,
            enabled = isBound && !isLoading,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(text = "Удалить дубликаты")
        }
    }
}