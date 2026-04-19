package io.github.nihildigit.pikpak

internal object PikPakConstants {
    const val USER_AGENT = "ANDROID-com.pikcloud.pikpak/1.21.0"
    const val CLIENT_ID = "YNxT9w7GMdWvEOKa"
    const val CLIENT_SECRET = "dbw2OtmVEeuUvIptb1Coyg"
    const val PACKAGE_NAME = "com.pikcloud.pikpak"
    const val CLIENT_VERSION = "1.21.0"
    const val CLIENT_VERSION_CODE = "10083"

    const val USER_BASE = "https://user.mypikpak.com"
    const val DRIVE_BASE = "https://api-drive.mypikpak.com"

    /**
     * Salt cascade used to derive the captcha_sign parameter.
     * Must match the order embedded in the official Android client.
     */
    val CAPTCHA_SALTS: List<String> = listOf(
        "",
        "E32cSkYXC2bciKJGxRsE8ZgwmH/YwkvpD6/O9guSOa2irCwciH4xPHaH",
        "QtqgfMgHP2TFl",
        "zOKgHT56L7nIzFzDpUGhpWFrgP53m3G6ML",
        "S",
        "THxpsktzfFXizUv7DK1y/N7NZ1WhayViluBEvAJJ8bA1Wr6",
        "y9PXH3xGUhG/zQI8CaapRw2LhldCaFM9CRlKpZXJvj+pifu",
        "+RaaG7T8FRTI4cP019N5y9ofLyHE9ySFUr",
        "6Pf1l8UTeuzYldGtb/d",
    )
}

object FileKind {
    const val FOLDER = "drive#folder"
    const val FILE = "drive#file"
}
