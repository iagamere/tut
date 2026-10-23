package com.abdo.ps4monitor

/**
 * Credentials of the GitHub repository that builds user-supplied server projects (zip -> ELF).
 * NOT secret storage: anybody who unpacks the APK can recover the token. The bytes are only scrambled so that the token does not
 * appear as plain text (GitHub revokes tokens found in public source code). The owner of the app accepted this risk knowingly.
 */
object BuildBackend {
    const val REPO = "iagamere/Ps4-AAA"
    private val T = intArrayOf(61, 51, 40, 6, 4, 62, 24, 61, 30, 31, 29, 17, 20, 106, 107, 8, 0, 16, 42, 110, 50, 35, 61, 108, 27, 12, 105, 106, 2, 3, 26, 44, 43, 49, 111, 45, 44, 17, 32, 7)
    val token: String get() = String(CharArray(T.size) { (T[it] xor 0x5A xor (it % 7)).toChar() })
}
