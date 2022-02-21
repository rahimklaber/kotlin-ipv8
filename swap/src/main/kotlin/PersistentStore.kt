package nl.tudelft.ipv8.jvm.swap

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.Settings
import com.russhwolf.settings.get
import com.russhwolf.settings.set

object PersistentStore {
    @OptIn(ExperimentalSettingsImplementation::class)
    private val settings = Settings()

    var xlmKey : String?
        get() = settings.getString("keys/xlm")
        set(value) = settings.set("keys/xlm",value)
    var btcKey : String?
        get() = settings.getString("keys/btc")
        set(value) = settings.set("keys/btc",value)

}
