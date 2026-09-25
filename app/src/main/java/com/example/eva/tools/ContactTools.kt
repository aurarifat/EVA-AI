package com.example.eva.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactEntry(
    val id: String,
    val displayName: String,
    val phoneNumber: String? = null
)

data class PhoneNumberAnalysis(
    val rawNumber: String,
    val countryCode: String,
    val countryName: String,
    val probableRegion: String,
    val isValidFormat: Boolean
)

class ContactTools(private val context: Context) {

    suspend fun searchContacts(query: String): List<ContactEntry> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<ContactEntry>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getString(idCol) else ""
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else "Unknown"
                    val num = if (numCol >= 0) cursor.getString(numCol) else null
                    contacts.add(ContactEntry(id, name, num))
                }
            }
        } catch (_: Exception) {}
        contacts
    }

    fun makePhoneCall(phoneNumberOrName: String): ToolExecutionResult {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(phoneNumberOrName.trim())}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolExecutionResult(true, "Preparing call to $phoneNumberOrName.")
        } catch (e: Exception) {
            ToolExecutionResult(false, "Could not open dialer: ${e.localizedMessage}")
        }
    }

    fun prepareSms(phoneNumber: String, body: String = ""): ToolExecutionResult {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${Uri.encode(phoneNumber.trim())}")
                putExtra("sms_body", body)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolExecutionResult(true, "Opening messaging app with drafted text.")
        } catch (e: Exception) {
            ToolExecutionResult(false, "Could not prepare SMS: ${e.localizedMessage}")
        }
    }

    fun analyzePhoneNumber(number: String): PhoneNumberAnalysis {
        val clean = number.replace(Regex("[^0-9+]"), "")
        val (code, country, region) = when {
            clean.startsWith("+880") || clean.startsWith("880") -> Triple("+880", "Bangladesh", "Dhaka / Regional Networks")
            clean.startsWith("+1") || (clean.length == 10 && !clean.startsWith("+")) -> Triple("+1", "United States / Canada", "North America NANP")
            clean.startsWith("+44") -> Triple("+44", "United Kingdom", "Ofcom UK National")
            clean.startsWith("+91") -> Triple("+91", "India", "Telecom Regulatory Authority of India")
            clean.startsWith("+49") -> Triple("+49", "Germany", "Federal Network Agency")
            clean.startsWith("+81") -> Triple("+81", "Japan", "Ministry of Internal Affairs")
            clean.startsWith("+61") -> Triple("+61", "Australia", "ACMA Telecommunications")
            clean.startsWith("+86") -> Triple("+86", "China", "MIIT National Numbering")
            else -> Triple("Unknown", "International", "Unspecified Region")
        }

        return PhoneNumberAnalysis(
            rawNumber = number,
            countryCode = code,
            countryName = country,
            probableRegion = region,
            isValidFormat = clean.length in 7..15
        )
    }
}
