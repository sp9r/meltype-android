package io.github.sp9r.meltype.core

import android.content.Context
import io.github.sp9r.meltype.glide.GlideDecoder

/**
 * Shares immutable dictionaries, emoji catalog, and glide decoder templates across
 * MainActivity and MeltypeImeService within the same Android process to avoid duplicate
 * allocations against the 256MB heap budget.
 */
object AppEngineHolder {
    @Volatile private var dictionaries: MeltypeDictionaries? = null
    @Volatile private var emojiCatalog: EmojiCatalog? = null
    @Volatile private var glideDecoder: GlideDecoder? = null

    fun getDictionaries(context: Context): MeltypeDictionaries {
        dictionaries?.let { return it }
        synchronized(this) {
            dictionaries?.let { return it }
            return MeltypeDictionaries(AssetDictionarySource(context.applicationContext)).also {
                dictionaries = it
            }
        }
    }

    fun getEmojiCatalog(context: Context): EmojiCatalog {
        emojiCatalog?.let { return it }
        synchronized(this) {
            emojiCatalog?.let { return it }
            return EmojiCatalog(AssetDictionarySource(context.applicationContext)).also {
                emojiCatalog = it
            }
        }
    }

    fun getGlideDecoder(context: Context): GlideDecoder {
        glideDecoder?.let { return it }
        synchronized(this) {
            glideDecoder?.let { return it }
            val dicts = getDictionaries(context)
            return GlideDecoder(dicts).also { glideDecoder = it }
        }
    }
}
