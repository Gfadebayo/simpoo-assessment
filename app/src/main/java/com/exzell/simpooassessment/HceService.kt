@file:OptIn(ExperimentalStdlibApi::class)

package com.exzell.simpooassessment

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.exzell.simpooassessment.data.NfcManager
import logcat.logcat
import java.io.UnsupportedEncodingException
import java.math.BigInteger
import java.util.Arrays

class HceService: HostApduService() {

    private val APDU_SELECT = byteArrayOf(
        0x00.toByte(), // CLA	- Class - Class of instruction
        0xA4.toByte(), // INS	- Instruction - Instruction code
        0x04.toByte(), // P1	- Parameter 1 - Instruction parameter 1
        0x00.toByte(), // P2	- Parameter 2 - Instruction parameter 2
        0x07.toByte(), // Lc field	- Number of bytes present in the data field of the command
        0xD2.toByte(),
        0x76.toByte(),
        0x00.toByte(),
        0x00.toByte(),
        0x85.toByte(),
        0x01.toByte(),
        0x01.toByte(), // NDEF Tag Application name
        0x00.toByte()  // Le field	- Maximum number of bytes expected in the data field of the response to the command
    )

    private val CAPABILITY_CONTAINER_OK = byteArrayOf(
        0x00.toByte(), // CLA	- Class - Class of instruction
        0xa4.toByte(), // INS	- Instruction - Instruction code
        0x00.toByte(), // P1	- Parameter 1 - Instruction parameter 1
        0x0c.toByte(), // P2	- Parameter 2 - Instruction parameter 2
        0x02.toByte(), // Lc field	- Number of bytes present in the data field of the command
        0xe1.toByte(), 0x03.toByte() // file identifier of the CC file
    )

    private val READ_CAPABILITY_CONTAINER = byteArrayOf(
        0x00.toByte(), // CLA	- Class - Class of instruction
        0xb0.toByte(), // INS	- Instruction - Instruction code
        0x00.toByte(), // P1	- Parameter 1 - Instruction parameter 1
        0x00.toByte(), // P2	- Parameter 2 - Instruction parameter 2
        0x0f.toByte()  // Lc field	- Number of bytes present in the data field of the command
    )

    // In the scenario that we have done a CC read, the same byte[] match
    // for ReadBinary would trigger and we don't want that in succession
    private var READ_CAPABILITY_CONTAINER_CHECK = false

    private val READ_CAPABILITY_CONTAINER_RESPONSE = byteArrayOf(
        0x00.toByte(), 0x11.toByte(), // CCLEN length of the CC file
        0x20.toByte(), // Mapping Version 2.0
        0xFF.toByte(), 0xFF.toByte(), // MLe maximum
        0xFF.toByte(), 0xFF.toByte(), // MLc maximum
        0x04.toByte(), // T field of the NDEF File Control TLV
        0x06.toByte(), // L field of the NDEF File Control TLV
        0xE1.toByte(), 0x04.toByte(), // File Identifier of NDEF file
        0xFF.toByte(), 0xFE.toByte(), // Maximum NDEF file size of 65534 bytes
        0x00.toByte(), // Read access without any security
        0xFF.toByte(), // Write access without any security
        0x90.toByte(), 0x00.toByte() // A_OKAY
    )

    private val NDEF_SELECT_OK = byteArrayOf(
        0x00.toByte(), // CLA	- Class - Class of instruction
        0xa4.toByte(), // Instruction byte (INS) for Select command
        0x00.toByte(), // Parameter byte (P1), select by identifier
        0x0c.toByte(), // Parameter byte (P1), select by identifier
        0x02.toByte(), // Lc field	- Number of bytes present in the data field of the command
        0xE1.toByte(), 0x04.toByte() // file identifier of the NDEF file retrieved from the CC file
    )

    private val NDEF_READ_BINARY = byteArrayOf(
        0x00.toByte(), // Class byte (CLA)
        0xb0.toByte() // Instruction byte (INS) for ReadBinary command
    )

    private val NDEF_READ_BINARY_NLEN = byteArrayOf(
        0x00.toByte(), // Class byte (CLA)
        0xb0.toByte(), // Instruction byte (INS) for ReadBinary command
        0x00.toByte(), 0x00.toByte(), // Parameter byte (P1, P2), offset inside the CC file
        0x02.toByte()  // Le field
    )

    private val A_OKAY = byteArrayOf(
        0x90.toByte(), // SW1	Status byte 1 - Command processing status
        0x00.toByte()   // SW2	Status byte 2 - Command processing qualifier
    )

    private val A_ERROR = byteArrayOf(
        0x6A.toByte(), // SW1	Status byte 1 - Command processing status
        0x82.toByte()   // SW2	Status byte 2 - Command processing qualifier
    )

    private val NDEF_ID = byteArrayOf(0xE1.toByte(), 0x04.toByte())

    private var NDEF_URI = NdefMessage(createTextRecord("en", "Simpoo Assessment", NDEF_ID))
    private var NDEF_URI_BYTES = NDEF_URI.toByteArray()
    private var NDEF_URI_LEN = fillByteArrayToFixedDimension(
        BigInteger.valueOf(NDEF_URI_BYTES.size.toLong()).toByteArray(), 2
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.also {
            if(it.hasExtra(NfcManager.EXTRA_MESSAGE)) {
               val message =  it.getStringExtra(NfcManager.EXTRA_MESSAGE) ?: ""

                NDEF_URI = NdefMessage(createTextRecord("en", message, NDEF_ID))

                NDEF_URI_BYTES = NDEF_URI.toByteArray()
                NDEF_URI_LEN = fillByteArrayToFixedDimension(
                    BigInteger.valueOf(NDEF_URI_BYTES.size.toLong()).toByteArray(), 2
                )
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if(commandApdu?.get(0) == 0xD2.toByte()) {
            logcat { "Command apdu starts with d2, fetching content"}

            val content = String(commandApdu.drop(1).toByteArray())

            logcat { "Content is $content" }
            val intent = Intent(NfcManager.ACTION_RESPONSE)
                .putExtra(NfcManager.EXTRA_RESPONSE, content)

            sendBroadcast(intent)
        }



        //
        // The following flow is based on Appendix E "Example of Mapping Version 2.0 Command Flow"
        // in the NFC Forum specification
        //
        logcat { "processCommandApdu() | incoming commandApdu: ${commandApdu?.toHexString()}" }

        //
        // First command: NDEF Tag Application select (Section 5.5.2 in NFC Forum spec)
        //
        if (APDU_SELECT.contentEquals(commandApdu)) {
            logcat { "APDU_SELECT triggered. Our Response: ${A_OKAY.toHexString()}" }
            return A_OKAY
        }

        val contentBytes = "Hello frommthe other side".encodeToByteArray()
        val apdu = ByteArray(contentBytes.size+1)
        apdu[0] = 0xD2.toByte()
        System.arraycopy(contentBytes, 0, apdu, 1, contentBytes.size)
        return apdu
//        //
//        // Second command: Capability Container select (Section 5.5.3 in NFC Forum spec)
//        //
//        if (CAPABILITY_CONTAINER_OK.contentEquals(commandApdu)) {
//            logcat { "CAPABILITY_CONTAINER_OK triggered. Our Response: ${A_OKAY.toHexString()}" }
//            return A_OKAY
//        }
//
//        //
//       // Third command: ReadBinary data from CC file (Section 5.5.4 in NFC Forum spec)
//        //
//        if (READ_CAPABILITY_CONTAINER.contentEquals(commandApdu) && !READ_CAPABILITY_CONTAINER_CHECK) {
//            logcat { "READ_CAPABILITY_CONTAINER triggered. Our Response: ${READ_CAPABILITY_CONTAINER_RESPONSE.toHexString()}" }
//            READ_CAPABILITY_CONTAINER_CHECK = true
//            return READ_CAPABILITY_CONTAINER_RESPONSE
//        }
//
//        //
//        // Fourth command: NDEF Select command (Section 5.5.5 in NFC Forum spec)
//        //
//        if (NDEF_SELECT_OK.contentEquals(commandApdu)) {
//            logcat { "NDEF_SELECT_OK triggered. Our Response: ${A_OKAY.toHexString()}" }
//            return A_OKAY
//        }
//
//        if (NDEF_READ_BINARY_NLEN.contentEquals(commandApdu)) {
//            // Build our response
//            val response = ByteArray(NDEF_URI_LEN.size + A_OKAY.size)
//            System.arraycopy(NDEF_URI_LEN, 0, response, 0, NDEF_URI_LEN.size)
//            System.arraycopy(A_OKAY, 0, response, NDEF_URI_LEN.size, A_OKAY.size)
//
//            logcat { "NDEF_READ_BINARY_NLEN triggered. Our Response: ${response.toHexString()}" }
//
//            READ_CAPABILITY_CONTAINER_CHECK = false
//            return response
//        }
//
//
//        if (commandApdu?.sliceArray(0..1)?.contentEquals(NDEF_READ_BINARY) == true) {
//            val offset = commandApdu.sliceArray(2..3).toHex().toInt(16)
//            val length = commandApdu.sliceArray(4..4).toHex().toInt(16)
//
//            val offset = intFromByteArray(commandApdu.sliceArray(2..3))
//            val length = intFromByteArray(commandApdu.sliceArray(4..4))
//
//            val fullResponse = ByteArray(NDEF_URI_LEN.size + NDEF_URI_BYTES.size)
//            System.arraycopy(NDEF_URI_LEN, 0, fullResponse, 0, NDEF_URI_LEN.size)
//            System.arraycopy(
//                NDEF_URI_BYTES,
//                0,
//                fullResponse,
//                NDEF_URI_LEN.size,
//                NDEF_URI_BYTES.size
//            )
//
//            logcat { "NDEF_READ_BINARY triggered. Full data: ${fullResponse.toHexString()}" }
//            logcat { "READ_BINARY - OFFSET: $offset - LEN: $length" }
//
//            val slicedResponse = fullResponse.sliceArray(offset until fullResponse.size)
//
//             //Build our response
//            val realLength = if (slicedResponse.size <= length) slicedResponse.size else length
//            val response = ByteArray(realLength + A_OKAY.size)
//
//            System.arraycopy(slicedResponse, 0, response, 0, realLength)
//            System.arraycopy(A_OKAY, 0, response, realLength, A_OKAY.size)
//
//            logcat { "NDEF_READ_BINARY triggered. Our Response: ${response.toHexString()}" }
//            READ_CAPABILITY_CONTAINER_CHECK = false
//            return response
        }

        //
        // We're doing something outside our scope
        //
//        logcat { "Wahala" }
//        return A_ERROR
//    }

    override fun onDeactivated(reason: Int) {
        logcat { "Hce deactivated with reason: $reason" }
    }
}

fun createTextRecord(language: String, text: String, id: ByteArray): NdefRecord {
    val languageBytes: ByteArray
    val textBytes: ByteArray
    try {
        languageBytes = language.toByteArray(charset("US-ASCII"))
        textBytes = text.toByteArray(charset("UTF-8"))
    } catch (e: UnsupportedEncodingException) {
        throw AssertionError(e)
    }

    val recordPayload = ByteArray(1 + (languageBytes.size and 0x03F) + textBytes.size)

    recordPayload[0] = (languageBytes.size and 0x03F).toByte()
    System.arraycopy(languageBytes, 0, recordPayload, 1, languageBytes.size and 0x03F)
    System.arraycopy(
        textBytes,
        0,
        recordPayload,
        1 + (languageBytes.size and 0x03F),
        textBytes.size
    )

    return NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, id, recordPayload)
}

fun fillByteArrayToFixedDimension(array: ByteArray, fixedSize: Int): ByteArray {
    if (array.size == fixedSize) {
        return array
    }

    val start = byteArrayOf(0x00.toByte())
    val filledArray = ByteArray(start.size + array.size)
    System.arraycopy(start, 0, filledArray, 0, start.size)
    System.arraycopy(array, 0, filledArray, start.size, array.size)
    return fillByteArrayToFixedDimension(filledArray, fixedSize)
}

fun intFromByteArray(from: ByteArray?): Int {
    if (from != null) {
        val offset = 0
        return from[offset].toInt() shl 24 and -16777216 or (from[offset + 1].toInt() shl 16 and 16711680) or (from[offset + 2].toInt() shl 8 and '\uff00'.code) or (from[offset + 3].toInt() and 255)
    } else {
        throw IllegalArgumentException("intFromByteArray input arg is null")
    }
}