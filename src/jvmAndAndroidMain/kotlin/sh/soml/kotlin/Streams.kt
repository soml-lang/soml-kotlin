package sh.soml.kotlin

import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer

/**
Reads a document from UTF-8 `stream` into a value of type `T`, as `Json.decodeFromStream()` does. The stream is read to its end, into memory, and not closed.

@throws SomlException When the bytes are not valid UTF-8, the document is not valid, or it does not fit `T`.
*/
public fun <T> Soml.decodeFromStream(deserializer: DeserializationStrategy<T>, stream: InputStream): T {
	// Reads the remaining bytes using stream-specific implementations available on both the JDK and Android 13.
	// Note: Not using readBytes() because API 33 provides readAllBytes() with stream-specific implementations.
	// Note: Not using transferTo(ByteArrayOutputStream) because its accumulator and final copy allocated more in the measured stream reads.
	return decodeFromByteArray(deserializer, stream.readAllBytes())
}

/**
Reads a document from UTF-8 `stream` into a value of type `T`. The stream is read to its end, into memory, and not closed.

```kotlin
val config = Path("config.soml").inputStream().use { Soml.decodeFromStream<Config>(it) }
```

@throws SomlException When the bytes are not valid UTF-8, the document is not valid, or it does not fit `T`.
*/
public inline fun <reified T> Soml.decodeFromStream(stream: InputStream): T = decodeFromStream(serializersModule.serializer(), stream)

/**
Writes `value` as a document to `stream` in UTF-8, as `Json.encodeToStream()` does. The stream is not flushed or closed.

@throws SomlException When the value is not an object or an array, or cannot be written.
*/
public fun <T> Soml.encodeToStream(serializer: SerializationStrategy<T>, value: T, stream: OutputStream) {
	// Note: Not using OutputStream.writer() because draining its byte buffer would flush the caller-owned stream.
	stream.write(encodeToByteArray(serializer, value))
}

/**
Writes `value` as a document to `stream` in UTF-8. The stream is not flushed or closed.

@throws SomlException When the value is not an object or an array, or cannot be written.
*/
public inline fun <reified T> Soml.encodeToStream(value: T, stream: OutputStream) {
	encodeToStream(serializersModule.serializer(), value, stream)
}
