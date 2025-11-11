package wtf.cluster.wireguardobfuscator

import android.content.Context
import androidx.annotation.StringRes

class Masking {
    data class MaskingType(
        val id: String,                       // stable key for config/DataStore, e.g. "stun"
        @StringRes val labelRes: Int,         // localized label resource
        val factory: ((Context) -> Masker)? = null   // factory to create Masker; null means "none"
    )

    companion object {
        private val items: List<MaskingType> = listOf(
            MaskingType(
                id = "none",
                labelRes = R.string.masking_none,
                factory = null                 // no masker object for "none"
            ),
            MaskingType(
                id = "stun",
                labelRes = R.string.masking_stun,
                factory = { context -> MaskerStun(context) }     // how to create an instance
            )
        )

        fun all(): List<MaskingType> = items

        fun findById(id: String): MaskingType? =
            items.firstOrNull { it.id == id }

        fun createMasker(id: String, context: Context): Masker? =
            findById(id)?.factory?.invoke(context)
    }
}