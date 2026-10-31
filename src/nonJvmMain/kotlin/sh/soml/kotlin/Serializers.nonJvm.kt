package sh.soml.kotlin

import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

internal actual val defaultSerializersModule: SerializersModule = EmptySerializersModule()
