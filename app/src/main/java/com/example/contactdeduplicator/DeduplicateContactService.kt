package com.example.contactdeduplicator

import android.app.Service
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Intent
import android.os.IBinder
import android.provider.ContactsContract
import android.util.Log




class DeduplicateContactService : Service() {
    // Binder реализует AIDL-интерфейс
    private val binder = object : IDeduplicator.Stub() {
        // Основной метод для удаления дубликатов return статус операции
        override fun deleteDuplContacts(): Int {
            return try {
                val duplicates = findDuplicateContacts()
                if (duplicates.isEmpty()) {
                    return NO_DUPLICATES_FOUND
                }
                deleteContacts(duplicates)
                SUCCESS
            } catch (e: Exception) {
                Log.e("ContactDel", "Error ", e)
                ERROR_OCCURRED
            }
        }
    }

    // Возвращает Binder при подключении клиента
    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    /* Находит дубликаты контактов  return Map где ключ - уникальный идентификатор контакта,
                                                   значение - список ID дубликатов
     */
    private fun findDuplicateContacts(): Map<String, List<Long>> {
        val contentResolver: ContentResolver = contentResolver
        val contactsMap = HashMap<String, MutableList<Long>>()

        // Запрашиваем только нужные поля контактов
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER

        )

        // Сортируем по имени для группировки дубликатов
        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE NOCASE"
        )

        // Обрабатываем результаты запроса
        cursor?.use {
            val idColumn = it.getColumnIndex(ContactsContract.Contacts._ID)
            val nameColumn = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val hasPhoneColumn = it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val name = it.getString(nameColumn)
                val hasPhone = it.getInt(hasPhoneColumn)

                if (name.isNullOrEmpty()) continue

                // Создаем уникальный ключ для сравнения контактов
                val contactKey = buildContactKey(contentResolver, id, name, hasPhone)
                contactsMap.getOrPut(contactKey) { mutableListOf() }.add(id)
            }
        }

        // Возвращаем только контакты с дубликатами
        return contactsMap.filter { it.value.size > 1 }
    }

    /* Создает уникальный ключ для сравнения контактов -
        contactId ID контакта, name Имя контакта, hasPhone Есть ли номер,
        return Строковый ключ для сравнения
     */
    private fun buildContactKey(
        contentResolver: ContentResolver,
        contactId: Long,
        name: String,
        hasPhone: Int
    ): String {
        val keyBuilder = StringBuilder(name.lowercase())

        // Добавляем телефоны к ключу, если они есть
        if (hasPhone > 0) {
            val phoneCursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )

            phoneCursor?.use {
                val numberColumn = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val phoneNumbers = mutableListOf<String>()
                while (it.moveToNext()) {
                    phoneNumbers.add(it.getString(numberColumn).orEmpty())
                }
                keyBuilder.append("|phones:").append(phoneNumbers.sorted().joinToString(","))
            }
        }

        // Добавляем email к ключу
        val emailCursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )

        emailCursor?.use {
            val emailColumn = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            val emails = mutableListOf<String>()
            while (it.moveToNext()) {
                emails.add(it.getString(emailColumn).orEmpty())
            }
            keyBuilder.append("|emails:").append(emails.sorted().joinToString(","))
        }

        return keyBuilder.toString()
    }

    /* Удаляет дубликаты контактов duplicates Map с дубликатами, return Количество удаленных контактов
     */
    private fun deleteContacts(duplicates: Map<String, List<Long>>): Int {
        val ops = ArrayList<ContentProviderOperation>()
        var deletedCount = 0

        // Для каждой группы дубликатов оставляем первый контакт
        duplicates.values.forEach { ids ->
            ids.drop(1).forEach { id ->
                ops.add(
                    ContentProviderOperation.newDelete(
                        ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                            .appendQueryParameter(
                                ContactsContract.CALLER_IS_SYNCADAPTER,
                                "true"
                            ).build()
                    )
                        .withSelection(
                            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                            arrayOf(id.toString())
                        )
                        .build()
                )
                deletedCount++
            }
        }

        // Применяем все операции удаления одной транзакцией
        if (ops.isNotEmpty()) {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }

        return deletedCount
    }

    companion object {
        const val SUCCESS = 0
        const val ERROR_OCCURRED = 1
        const val NO_DUPLICATES_FOUND = 2
    }
}